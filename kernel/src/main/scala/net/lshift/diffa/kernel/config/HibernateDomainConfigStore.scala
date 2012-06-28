/**
 * Copyright (C) 2010-2011 LShift Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lshift.diffa.kernel.config

import net.lshift.diffa.kernel.util.db.HibernateQueryUtils
import org.hibernate.SessionFactory
import scala.collection.JavaConversions._
import net.lshift.diffa.kernel.frontend._
import net.lshift.diffa.kernel.hooks.HookManager
import java.util.List
import net.lshift.diffa.schema.jooq.{DatabaseFacade => JooqDatabaseFacade}
import net.lshift.diffa.schema.tables.Domains.DOMAINS
import net.lshift.diffa.schema.tables.Members.MEMBERS
import net.lshift.diffa.schema.tables.ConfigOptions.CONFIG_OPTIONS
import net.lshift.diffa.schema.tables.RepairActions.REPAIR_ACTIONS
import net.lshift.diffa.schema.tables.Escalations.ESCALATIONS
import net.lshift.diffa.schema.tables.PairReports.PAIR_REPORTS
import net.lshift.diffa.schema.tables.Pair.PAIR
import net.lshift.diffa.schema.tables.PairViews.PAIR_VIEWS
import net.lshift.diffa.schema.tables.StoreCheckpoints.STORE_CHECKPOINTS
import net.lshift.diffa.schema.tables.UserItemVisibility.USER_ITEM_VISIBILITY
import net.lshift.diffa.schema.tables.Endpoint.ENDPOINT
import net.lshift.diffa.schema.tables.EndpointViews.ENDPOINT_VIEWS
import org.jooq.{Result, Record}
import net.lshift.diffa.kernel.naming.CacheName._
import net.lshift.diffa.kernel.util.MissingObjectException
import net.lshift.diffa.kernel.lifecycle.{PairLifecycleAware, DomainLifecycleAware}
import net.lshift.diffa.kernel.util.cache.{KeyPredicate, CacheProvider}
import reflect.BeanProperty
import collection.mutable
import java.util
import collection.mutable.ListBuffer
import org.jooq.impl.Factory
;

class HibernateDomainConfigStore(val sessionFactory: SessionFactory,
                                 jooq:JooqDatabaseFacade,
                                 hookManager:HookManager,
                                 cacheProvider:CacheProvider,
                                 membershipListener:DomainMembershipAware)
    extends DomainConfigStore
    with DomainLifecycleAware {

  val hook = hookManager.createDifferencePartitioningHook(jooq)

  private val pairEventSubscribers = new ListBuffer[PairLifecycleAware]
  def registerPairEventListener(p:PairLifecycleAware) = pairEventSubscribers += p

  private val cachedConfigVersions = cacheProvider.getCachedMap[String,Int]("domain.config.versions")
  private val cachedPairs = cacheProvider.getCachedMap[String, List[DomainPairDef]]("domain.pairs")
  private val cachedPairsByKey = cacheProvider.getCachedMap[DomainPairKey, DomainPairDef]("domain.pairs.by.key")
  private val cachedEndpoints = cacheProvider.getCachedMap[String, List[EndpointDef]]("domain.endpoints")
  private val cachedEndpointsByKey = cacheProvider.getCachedMap[DomainEndpointKey, EndpointDef]("domain.endpoints.by.key")
  private val cachedPairsByEndpoint = cacheProvider.getCachedMap[DomainEndpointKey, List[DomainPairDef]]("domain.pairs.by.endpoint")

  // Config options
  private val cachedDomainConfigOptionsMap = cacheProvider.getCachedMap[String, java.util.Map[String,String]](DOMAIN_CONFIG_OPTIONS_MAP)
  private val cachedDomainConfigOptions = cacheProvider.getCachedMap[DomainConfigKey, String](DOMAIN_CONFIG_OPTIONS)

  // Members
  private val cachedMembers = cacheProvider.getCachedMap[String, java.util.List[Member]](USER_DOMAIN_MEMBERS)

  // Escalations
  private val cachedEscalations = cacheProvider.getCachedMap[DomainPairKey, java.util.List[EscalationDef]](DOMAIN_ESCALATIONS)

  // Repair Actions
  private val cachedRepairActions = cacheProvider.getCachedMap[DomainPairKey, java.util.List[RepairActionDef]](DOMAIN_REPAIR_ACTIONS)

  // Pair Reports
  private val cachedPairReports = cacheProvider.getCachedMap[DomainPairKey, java.util.List[PairReportDef]](DOMAIN_PAIR_REPORTS)

  def reset {
    cachedConfigVersions.evictAll()
    cachedPairs.evictAll()
    cachedPairsByKey.evictAll()
    cachedEndpoints.evictAll()
    cachedEndpointsByKey.evictAll()
    cachedPairsByEndpoint.evictAll()

    cachedDomainConfigOptionsMap.evictAll()
    cachedDomainConfigOptions.evictAll()

    cachedMembers.evictAll()

    cachedEscalations.evictAll()

    cachedRepairActions.evictAll()

    cachedPairReports.evictAll()
  }

  private def invalidatePairReportsCache(domain:String) = {
    cachedPairReports.keySubset(PairByDomainPredicate(domain)).evictAll()
  }

  private def invalidateEscalationCache(domain:String) = {
    cachedEscalations.keySubset(PairByDomainPredicate(domain)).evictAll()
  }

  private def invalidateRepairActionCache(domain:String) = {
    cachedRepairActions.keySubset(PairByDomainPredicate(domain)).evictAll()
  }

  private def invalidateMembershipCache(domain:String) = {
    cachedMembers.evict(domain)
  }

  private def invalidateConfigCaches(domain:String) = {
    cachedDomainConfigOptionsMap.evict(domain)
    cachedDomainConfigOptions.keySubset(ConfigOptionByDomainPredicate(domain)).evictAll()
  }

  private def invalidateAllCaches(domain:String) = {
    cachedConfigVersions.evict(domain)
    cachedEndpoints.evict(domain)
    cachedPairs.evict(domain)
    cachedPairsByEndpoint.keySubset(EndpointByDomainPredicate(domain)).evictAll()
    cachedPairsByKey.keySubset(PairByDomainPredicate(domain)).evictAll()
    cachedEndpointsByKey.keySubset(EndpointByDomainPredicate(domain)).evictAll()

    invalidateConfigCaches(domain)

    invalidateMembershipCache(domain)
    invalidateEscalationCache(domain)
    invalidateRepairActionCache(domain)
    invalidatePairReportsCache(domain)
  }

  private def invalidateEndpointCachesOnly(domain:String, endpointName: String) = {
    cachedEndpoints.evict(domain)
    cachedPairsByEndpoint.keySubset(PairByDomainAndEndpointPredicate(domain, endpointName)).evictAll()
    cachedEndpointsByKey.evict(DomainEndpointKey(domain,endpointName))

    // TODO This is a very coarse grained invalidation of the pair caches - this could be made finer at some stage
    cachedPairs.evict(domain)
    cachedPairsByKey.keySubset(PairByDomainPredicate(domain)).evictAll()
  }

  private def invalidatePairCachesOnly(domain:String) = {
    cachedPairs.evict(domain)
    cachedPairsByKey.keySubset(PairByDomainPredicate(domain)).evictAll()
    cachedPairsByEndpoint.keySubset(EndpointByDomainPredicate(domain)).evictAll()
  }

  def onDomainUpdated(domain: String) = invalidateAllCaches(domain)
  def onDomainRemoved(domain: String) = invalidateAllCaches(domain)



  def createOrUpdateEndpoint(domainName:String, e: EndpointDef) : DomainEndpointDef = {

    jooq.execute(t => {

      // TODO create categories

      t.insertInto(ENDPOINT).
          set(ENDPOINT.DOMAIN, domainName).
          set(ENDPOINT.NAME, e.name).
          set(ENDPOINT.COLLATION_TYPE, e.collation).
          set(ENDPOINT.CONTENT_RETRIEVAL_URL, e.contentRetrievalUrl).
          set(ENDPOINT.SCAN_URL, e.scanUrl).
          set(ENDPOINT.VERSION_GENERATION_URL, e.versionGenerationUrl).
          set(ENDPOINT.INBOUND_URL, e.inboundUrl).
        onDuplicateKeyUpdate().
          set(ENDPOINT.COLLATION_TYPE, e.collation).
          set(ENDPOINT.CONTENT_RETRIEVAL_URL, e.contentRetrievalUrl).
          set(ENDPOINT.SCAN_URL, e.scanUrl).
          set(ENDPOINT.VERSION_GENERATION_URL, e.versionGenerationUrl).
          set(ENDPOINT.INBOUND_URL, e.inboundUrl).
        execute()

      // Update the view definitions

      t.delete(ENDPOINT_VIEWS).
        where(ENDPOINT_VIEWS.NAME.notIn(e.views.map(v => v.name))).
          and(ENDPOINT_VIEWS.DOMAIN.equal(domainName)).
          and(ENDPOINT_VIEWS.ENDPOINT.equal(e.name)).
        execute()

      e.views.foreach(v => {
        t.insertInto(ENDPOINT_VIEWS).
            set(ENDPOINT_VIEWS.DOMAIN, domainName).
            set(ENDPOINT_VIEWS.ENDPOINT, e.name).
            set(ENDPOINT_VIEWS.NAME, v.name).
          onDuplicateKeyIgnore()
      })

      upgradeConfigVersion(t, domainName)

    })

    invalidateEndpointCachesOnly(domainName, e.name)

    DomainEndpointDef(
      domain = domainName,
      name = e.name,
      collation = e.collation,
      contentRetrievalUrl = e.contentRetrievalUrl,
      scanUrl = e.scanUrl,
      versionGenerationUrl = e.versionGenerationUrl,
      inboundUrl = e.inboundUrl
      // TODO categories
    )
  }

  def deleteEndpoint(domain:String, name: String) = {

    jooq.execute(t => {

      // Remove all pairs that reference the endpoint

      val results = t.select(PAIR.DOMAIN, PAIR.PAIR_KEY).
                      from(PAIR).
                      where(PAIR.DOMAIN.equal(domain)).
                        and(PAIR.UPSTREAM.equal(name).
                            or(PAIR.DOWNSTREAM.equal(name))).fetch()

      results.iterator().foreach(r => {
        val ref = DiffaPairRef(r.getValue(PAIR.PAIR_KEY), r.getValue(PAIR.DOMAIN))
        deletePairWithDependencies(t, ref)
      })

      t.delete(ENDPOINT_VIEWS).
        where(ENDPOINT_VIEWS.DOMAIN.equal(domain)).
          and(ENDPOINT_VIEWS.ENDPOINT.equal(name)).
        execute()

      t.delete(ENDPOINT).
        where(ENDPOINT.DOMAIN.equal(domain)).
          and(ENDPOINT.NAME.equal(name)).
        execute()

      upgradeConfigVersion(t, domain)

    })

    invalidatePairCachesOnly(domain)
    invalidateEndpointCachesOnly(domain, name)

  }

  def listEndpoints(domain:String): Seq[EndpointDef] = cachedEndpoints.readThrough(domain, () => {
    jooq.execute(t => {
      val results = t.select().
                      from(ENDPOINT).
                      where(ENDPOINT.DOMAIN.equal(domain)).
                      fetch()

      val endpoints = new java.util.ArrayList[EndpointDef]()

      results.iterator().foreach(r => endpoints.add(recordToEndpoint(r)))

      endpoints
    })
  }).toSeq


  private def listEndpointCategories(t:Factory, domain:String) : java.util.List[CategoryDescriptor] = {
    null
  }

  def createOrUpdatePair(domain:String, pair: PairDef) = {

    jooq.execute(t => {
      t.insertInto(PAIR).
          set(PAIR.DOMAIN, domain).
          set(PAIR.PAIR_KEY, pair.key).
          set(PAIR.UPSTREAM, pair.upstreamName).
          set(PAIR.DOWNSTREAM, pair.downstreamName).
          set(PAIR.ALLOW_MANUAL_SCANS, pair.allowManualScans).
          set(PAIR.MATCHING_TIMEOUT, pair.matchingTimeout.asInstanceOf[Integer]).
          set(PAIR.SCAN_CRON_SPEC, pair.scanCronSpec).
          set(PAIR.VERSION_POLICY_NAME, pair.versionPolicyName).
        onDuplicateKeyUpdate().
          set(PAIR.UPSTREAM, pair.upstreamName).
          set(PAIR.DOWNSTREAM, pair.downstreamName).
          set(PAIR.ALLOW_MANUAL_SCANS, pair.allowManualScans).
          set(PAIR.MATCHING_TIMEOUT, pair.matchingTimeout.asInstanceOf[Integer]).
          set(PAIR.SCAN_CRON_SPEC, pair.scanCronSpec).
          set(PAIR.VERSION_POLICY_NAME, pair.versionPolicyName).
        execute()

      // Update the view definitions

      t.delete(PAIR_VIEWS).
        where(PAIR_VIEWS.NAME.notIn(pair.views.map(p => p.name))).
          and(PAIR_VIEWS.DOMAIN.equal(domain)).
          and(PAIR_VIEWS.NAME.equal(pair.key)).
        execute()

      pair.views.foreach(v => {
        t.insertInto(PAIR_VIEWS).
            set(PAIR_VIEWS.DOMAIN, domain).
            set(PAIR_VIEWS.PAIR, pair.key).
            set(PAIR_VIEWS.NAME, v.name).
            set(PAIR_VIEWS.SCAN_CRON_SPEC, v.scanCronSpec).
          onDuplicateKeyUpdate().
            set(PAIR_VIEWS.SCAN_CRON_SPEC, v.scanCronSpec).
          execute()
      })

      upgradeConfigVersion(t, domain)

    })

    invalidatePairCachesOnly(domain)

    hook.pairCreated(domain, pair.key)
  }

  def deletePair(domain:String, key: String) = {
    jooq.execute(t => {
      val ref = DiffaPairRef(key,domain)
      invalidatePairCachesOnly(domain)
      deletePairWithDependencies(t, ref)
      upgradeConfigVersion(t, domain)
      pairEventSubscribers.foreach(_.onPairDeleted(ref))
      hook.pairRemoved(domain, key)
    })

    // TODO kill this
    HibernateQueryUtils.forceHibernateCacheEviction(sessionFactory)
  }

  def listPairs(domain:String) = cachedPairs.readThrough(domain, () => listPairsInternal(domain))

  def listPairsForEndpoint(domain:String, endpoint:String) =
    cachedPairsByEndpoint.readThrough(DomainEndpointKey(domain, endpoint), () => listPairsInternal(domain, Some(endpoint)))

  private def listPairsInternal(domain:String, endpoint:Option[String] = None) : Seq[DomainPairDef] = jooq.execute(t => {


    val baseQuery = t.select(PAIR.getFields).
                      select(PAIR_VIEWS.NAME, PAIR_VIEWS.SCAN_CRON_SPEC).
                      from(PAIR).
                        leftOuterJoin(PAIR_VIEWS).
                          on(PAIR_VIEWS.PAIR.equal(PAIR.PAIR_KEY)).
                          and(PAIR_VIEWS.DOMAIN.equal(PAIR.DOMAIN)).
                      where(PAIR.DOMAIN.equal(domain))

    val query = endpoint match {
      case None       => baseQuery
      case Some(name) => baseQuery.and(PAIR.UPSTREAM.equal(name).or(PAIR.DOWNSTREAM.equal(name)))
    }

    val results = query.fetch()

    val compressed = new mutable.HashMap[String, DomainPairDef]()

    def compressionKey(pairKey:String) = domain + "/" + pairKey

    results.iterator().map(record => {
      val pairKey = record.getValue(PAIR.PAIR_KEY)
      val compressedKey = compressionKey(pairKey)
      val pair = compressed.getOrElseUpdate(compressedKey,
        DomainPairDef(
          domain = record.getValue(PAIR.DOMAIN),
          key = record.getValue(PAIR.PAIR_KEY),
          upstreamName = record.getValue(PAIR.UPSTREAM),
          downstreamName = record.getValue(PAIR.DOWNSTREAM),
          versionPolicyName = record.getValue(PAIR.VERSION_POLICY_NAME),
          scanCronSpec = record.getValue(PAIR.SCAN_CRON_SPEC),
          matchingTimeout = record.getValue(PAIR.MATCHING_TIMEOUT),
          allowManualScans = record.getValue(PAIR.ALLOW_MANUAL_SCANS),
          views = new util.ArrayList[PairViewDef]()
        )
      )

      val viewScanCronSpec = record.getValue(PAIR_VIEWS.SCAN_CRON_SPEC)
      val viewName = record.getValue(PAIR_VIEWS.NAME)

      if (viewName != null) {
        pair.views.add(PairViewDef(
          name = viewName,
          scanCronSpec = viewScanCronSpec
        ))
      }

      pair

    }).toList
  })

  def listEscalationsForPair(domain:String, pairKey: String) : Seq[EscalationDef] = {
    cachedEscalations.readThrough(DomainPairKey(domain, pairKey), () => jooq.execute(t => {
      val results = t.select().
                      from(ESCALATIONS).
                      where(ESCALATIONS.DOMAIN.equal(domain)).
                      and(ESCALATIONS.PAIR_KEY.equal(pairKey)).
                      fetch()

      mapResultsToList(results, recordToEscalation)
    }))
  }

  // TODO Currently this is an uncached call because rather than putting in yet another cache
  // it would be nice to query cachedEscalations, since that contains the data in any case.
  // However, to maintain coherency, we would need to lister to evictions from that cache,
  // so that we can make sure that we're not reading stale data
  def listEscalations(domain:String) = jooq.execute(t => {
    val results = t.select().
                    from(ESCALATIONS).
                    where(ESCALATIONS.DOMAIN.equal(domain)).
                    fetch()

    mapResultsToList(results, recordToEscalation)
  })

  def deleteEscalation(domain:String, name: String, pairKey: String) = {

    jooq.execute(t => {
      t.delete(ESCALATIONS).
        where(ESCALATIONS.DOMAIN.equal(domain)).
        and(ESCALATIONS.PAIR_KEY.equal(pairKey)).
        and(ESCALATIONS.NAME.equal(name)).
        execute()
    })

    invalidateEscalationCache(domain)
  }

  def createOrUpdateEscalation(domain:String, e: EscalationDef) = {

    jooq.execute(t => {
      t.insertInto(ESCALATIONS).
        set(ESCALATIONS.DOMAIN, domain).
        set(ESCALATIONS.PAIR_KEY, e.pair).
        set(ESCALATIONS.NAME, e.name).
        set(ESCALATIONS.ACTION, e.action).
        set(ESCALATIONS.ACTION_TYPE, e.actionType).
        set(ESCALATIONS.EVENT, e.event).
        set(ESCALATIONS.ORIGIN, e.origin).
        onDuplicateKeyUpdate().
        set(ESCALATIONS.ACTION, e.action).
        set(ESCALATIONS.ACTION_TYPE, e.actionType).
        set(ESCALATIONS.EVENT, e.event).
        set(ESCALATIONS.ORIGIN, e.origin).
        execute()

    })

    invalidateEscalationCache(domain)
  }

  def listReportsForPair(domain:String, pairKey: String) : Seq[PairReportDef] = {
    cachedPairReports.readThrough(DomainPairKey(domain, pairKey), () => jooq.execute(t => {
      val results = t.select().
        from(PAIR_REPORTS).
        where(PAIR_REPORTS.DOMAIN.equal(domain)).
        and(PAIR_REPORTS.PAIR_KEY.equal(pairKey)).
        fetch()

      mapResultsToList(results, recordToPairReport)
    }))
  }

  // TODO see comment about listEscalations/1
  def listReports(domain:String) = jooq.execute(t => {
    val results = t.select().
      from(PAIR_REPORTS).
      where(PAIR_REPORTS.DOMAIN.equal(domain)).
      fetch()

    mapResultsToList(results, recordToPairReport)
  })

  def deleteReport(domain:String, name: String, pairKey: String) = {
    jooq.execute(t => {
      t.delete(PAIR_REPORTS).
        where(PAIR_REPORTS.DOMAIN.equal(domain)).
        and(PAIR_REPORTS.PAIR_KEY.equal(pairKey)).
        and(PAIR_REPORTS.NAME.equal(name)).
        execute()
    })

    invalidatePairReportsCache(domain)
  }

  def createOrUpdateReport(domain:String, r: PairReportDef) = {
    jooq.execute(t => {
      t.insertInto(PAIR_REPORTS).
          set(PAIR_REPORTS.DOMAIN, domain).
          set(PAIR_REPORTS.PAIR_KEY, r.pair).
          set(PAIR_REPORTS.NAME, r.name).
          set(PAIR_REPORTS.REPORT_TYPE, r.reportType).
          set(PAIR_REPORTS.TARGET, r.target).
        onDuplicateKeyUpdate().
          set(PAIR_REPORTS.REPORT_TYPE, r.reportType).
          set(PAIR_REPORTS.TARGET, r.target).
        execute()
    })

    invalidatePairReportsCache(domain)
  }

  // TODO Not cached right now
  def getPairReportDef(domain:String, name: String, pairKey: String) = jooq.execute(t => {
    val record = t.select().
                   from(PAIR_REPORTS).
                   where(PAIR_REPORTS.DOMAIN.equal(domain)).
                     and(PAIR_REPORTS.PAIR_KEY.equal(pairKey)).
                     and(PAIR_REPORTS.NAME.equal(name)).
                   fetchOne()

    if (record == null) {
      throw new MissingObjectException("pair report")
    }
    else {
      recordToPairReport(record)
    }

  })

  def createOrUpdateRepairAction(domain:String, a: RepairActionDef) = {
    jooq.execute(t => {
      t.insertInto(REPAIR_ACTIONS).
          set(REPAIR_ACTIONS.DOMAIN, domain).
          set(REPAIR_ACTIONS.PAIR_KEY, a.pair).
          set(REPAIR_ACTIONS.NAME, a.name).
          set(REPAIR_ACTIONS.SCOPE, a.scope).
          set(REPAIR_ACTIONS.URL, a.url).
        onDuplicateKeyUpdate().
          set(REPAIR_ACTIONS.SCOPE, a.scope).
          set(REPAIR_ACTIONS.URL, a.url).
        execute()
    })

    invalidateRepairActionCache(domain)
  }


  def deleteRepairAction(domain:String, name: String, pairKey: String) = {
    jooq.execute(t => {
      t.delete(REPAIR_ACTIONS).
        where(REPAIR_ACTIONS.DOMAIN.equal(domain)).
          and(REPAIR_ACTIONS.PAIR_KEY.equal(pairKey)).
          and(REPAIR_ACTIONS.NAME.equal(name)).
        execute()
    })

    invalidateRepairActionCache(domain)
  }

  def listRepairActionsForPair(domain:String, pairKey: String) : Seq[RepairActionDef] = {
    cachedRepairActions.readThrough(DomainPairKey(domain, pairKey), () => jooq.execute(t => {
      val results = t.select().
                      from(REPAIR_ACTIONS).
                      where(REPAIR_ACTIONS.DOMAIN.equal(domain)).
                        and(REPAIR_ACTIONS.PAIR_KEY.equal(pairKey)).
                      fetch()

      mapResultsToList(results, recordToRepairAction)
    }))
  }

  def listRepairActions(domain:String) : Seq[RepairActionDef] = jooq.execute(t => {
    val results = t.select().
                    from(REPAIR_ACTIONS).
                    where(REPAIR_ACTIONS.DOMAIN.equal(domain)).
                    fetch()

    mapResultsToList(results, recordToRepairAction)
  })

  // TODO Not cached right now
  def getRepairActionDef(domain:String, name: String, pairKey: String) = jooq.execute(t => {
    val record = t.select().
                   from(REPAIR_ACTIONS).
                   where(REPAIR_ACTIONS.DOMAIN.equal(domain)).
                     and(REPAIR_ACTIONS.PAIR_KEY.equal(pairKey)).
                     and(REPAIR_ACTIONS.NAME.equal(name)).
                   fetchOne()

    if (record == null) {
      throw new MissingObjectException("repair action")
    }
    else {
      recordToRepairAction(record)
    }

  })

  def getEndpointDef(domain:String, endpoint: String) = {
    cachedEndpointsByKey.readThrough(DomainEndpointKey(domain, endpoint), () => {
      jooq.execute(t => {
        val record = t.select().
                       from(ENDPOINT).
                       where(ENDPOINT.DOMAIN.equal(domain)).
                         and(ENDPOINT.NAME.equal(endpoint)).
                       fetchOne()

        if (record == null) {
          throw new MissingObjectException("endpoint")
        }
        else {
          recordToEndpoint(record)
        }
      })
    })
  }

  @Deprecated def getEndpoint(domain:String, endpoint: String) = {

    val endpointDef = getEndpointDef(domain, endpoint)

    val ep = Endpoint(
      name = endpointDef.name,
      domain = Domain(name = domain),
      scanUrl = endpointDef.scanUrl,
      versionGenerationUrl = endpointDef.versionGenerationUrl,
      contentRetrievalUrl = endpointDef.contentRetrievalUrl,
      collation = endpointDef.collation,
      categories = endpointDef.categories
    )

    val views = new util.HashSet[EndpointView]()

    endpointDef.views.foreach(v => {
      views.add(EndpointView(
        name = v.name,
        endpoint = ep,
        categories = v.categories
      ))
    })

    ep.setViews(views)

    ep
  }


  def getPairDef(domain:String, key: String) = cachedPairsByKey.readThrough(DomainPairKey(domain,key), () => jooq.execute { t =>

    val result =
      t.select(PAIR.getFields).
        select(PAIR_VIEWS.NAME, PAIR_VIEWS.SCAN_CRON_SPEC).
        from(PAIR).
          leftOuterJoin(PAIR_VIEWS).
            on(PAIR_VIEWS.PAIR.equal(PAIR.PAIR_KEY)).
            and(PAIR_VIEWS.DOMAIN.equal(PAIR.DOMAIN)).
        where(PAIR.DOMAIN.equal(domain).
          and(PAIR.PAIR_KEY.equal(key)).
          and(
            PAIR_VIEWS.DOMAIN.equal(domain).
            and(PAIR_VIEWS.PAIR.equal(key)).
            orNotExists(
              t.selectOne().
                from(PAIR_VIEWS).
                where(
                  PAIR_VIEWS.DOMAIN.equal(domain).
                  and(PAIR_VIEWS.PAIR.equal(key))
              )
            )
          )
        ).fetch()

    if (result.size() == 0) {
      //throw new MissingObjectException(domain + "/" + key)

      // TODO Ideally this code should throw something more descriptive like the above error
      // but for now, I'd like to keep this patch small

      throw new MissingObjectException("pair")
    }
    else {
      ResultMappingUtil.singleParentRecordToDomainPairDef(result)
    }

  })

  def getConfigVersion(domain:String) = cachedConfigVersions.readThrough(domain, () => jooq.execute(t => {

    val result = t.select(DOMAINS.CONFIG_VERSION).
                   from(DOMAINS).
                   where(DOMAINS.NAME.equal(domain)).
                   fetchOne()

    if (result == null) {
      throw new MissingObjectException("domain")
    }
    else {
      result.getValue(DOMAINS.CONFIG_VERSION)
    }

  }))

  private def mapResultsToList[T](results:Result[Record], rowMapper:Record => T) = {
    val escalations = new java.util.ArrayList[T]()
    results.iterator().foreach(r => escalations.add(rowMapper(r)))
    escalations
  }

  private def recordToEscalation(record:Record) : EscalationDef = {
    EscalationDef(
      pair = record.getValue(ESCALATIONS.PAIR_KEY),
      name = record.getValue(ESCALATIONS.NAME),
      action = record.getValue(ESCALATIONS.ACTION),
      actionType = record.getValue(ESCALATIONS.ACTION_TYPE),
      event = record.getValue(ESCALATIONS.EVENT),
      origin = record.getValue(ESCALATIONS.ORIGIN))
  }

  private def recordToPairReport(record:Record) : PairReportDef = {
    PairReportDef(
      pair = record.getValue(PAIR_REPORTS.PAIR_KEY),
      name = record.getValue(PAIR_REPORTS.NAME),
      target = record.getValue(PAIR_REPORTS.TARGET),
      reportType = record.getValue(PAIR_REPORTS.REPORT_TYPE)
    )
  }

  private def recordToRepairAction(record:Record) : RepairActionDef = {
    RepairActionDef(
      pair = record.getValue(REPAIR_ACTIONS.PAIR_KEY),
      name = record.getValue(REPAIR_ACTIONS.NAME),
      scope = record.getValue(REPAIR_ACTIONS.SCOPE),
      url = record.getValue(REPAIR_ACTIONS.URL)
    )
  }

  private def recordToEndpoint(record:Record) : EndpointDef = {
    EndpointDef(
      name = record.getValue(ENDPOINT.NAME),
      scanUrl = record.getValue(ENDPOINT.SCAN_URL),
      contentRetrievalUrl = record.getValue(ENDPOINT.CONTENT_RETRIEVAL_URL),
      versionGenerationUrl = record.getValue(ENDPOINT.VERSION_GENERATION_URL),
      inboundUrl = record.getValue(ENDPOINT.INBOUND_URL),
      categories = null,
      views = null,
      collation = record.getValue(ENDPOINT.COLLATION_TYPE)
    )
  }

  /**
   * Force the DB to uprev the config version column for this particular domain
   */
  private def upgradeConfigVersion(t:Factory, domain:String) {

    cachedConfigVersions.evict(domain)

    t.update(DOMAINS).
      set(DOMAINS.CONFIG_VERSION, DOMAINS.CONFIG_VERSION.add(1)).
      where(DOMAINS.NAME.equal(domain)).
      execute()
  }

  def allConfigOptions(domain:String) = cachedDomainConfigOptionsMap.readThrough(domain, () => jooq.execute( t => {
    val results = t.select(CONFIG_OPTIONS.OPT_KEY, CONFIG_OPTIONS.OPT_VAL).
      from(CONFIG_OPTIONS).
      where(CONFIG_OPTIONS.DOMAIN.equal(domain)).fetch()

    val configs = new java.util.HashMap[String,String]()

    results.iterator().foreach(r => {
      configs.put(r.getValue(CONFIG_OPTIONS.OPT_KEY), r.getValue(CONFIG_OPTIONS.OPT_VAL))
    })

    configs
  })).toMap


  def maybeConfigOption(domain:String, key:String) = {

    val option = cachedDomainConfigOptions.readThrough(DomainConfigKey(domain,key), () => jooq.execute( t => {

      val record = t.select(CONFIG_OPTIONS.OPT_VAL).
                     from(CONFIG_OPTIONS).
                     where(CONFIG_OPTIONS.DOMAIN.equal(domain)).
                       and(CONFIG_OPTIONS.OPT_KEY.equal(key)).
                     fetchOne()

      if (record != null) {
        record.getValue(CONFIG_OPTIONS.OPT_VAL)
      }
      else {
        // Insert a null byte into as a value for this key in the cache to denote that this key does not
        // exist and should not get queried for against the the underlying database
        "\u0000"
      }

    }))

    option match {
      case "\u0000"     => None
      case value        => Some(value)
    }
  }

  def configOptionOrDefault(domain:String, key: String, defaultVal: String) =
    maybeConfigOption(domain, key) match {
      case Some(str) => str
      case None      => defaultVal
    }

  def setConfigOption(domain:String, key:String, value:String) = {
    jooq.execute(t => {
      t.insertInto(CONFIG_OPTIONS).
        set(CONFIG_OPTIONS.DOMAIN, domain).
        set(CONFIG_OPTIONS.OPT_KEY, key).
        set(CONFIG_OPTIONS.OPT_VAL, value).
      onDuplicateKeyUpdate().
        set(CONFIG_OPTIONS.OPT_VAL, value).
      execute()
    })

    invalidateConfigCaches(domain)
  }

  def clearConfigOption(domain:String, key:String) = {
    jooq.execute(t => {
      t.delete(CONFIG_OPTIONS).
        where(CONFIG_OPTIONS.DOMAIN.equal(domain)).
        and(CONFIG_OPTIONS.OPT_KEY.equal(key)).
      execute()
    })

    // TODO This is a very coarse grained invalidation
    invalidateConfigCaches(domain)
  }

  private def deletePairWithDependencies(t:Factory, pair:DiffaPairRef) = {
    deleteRepairActionsByPair(t, pair)
    deleteEscalationsByPair(t, pair)
    deleteReportsByPair(t, pair)
    deletePairViewsByPair(t, pair)
    deleteStoreCheckpointsByPair(t, pair)
    deleteUserItemsByPair(t, pair)
    deletePairWithoutDependencies(t, pair)
  }

  private def deletePairWithoutDependencies(t:Factory, pair:DiffaPairRef) = {
    val deleted = t.delete(PAIR).
      where(PAIR.DOMAIN.equal(pair.domain)).
      and(PAIR.PAIR_KEY.equal(pair.key)).
      execute()

    if (deleted == 0) {
      throw new MissingObjectException(pair.identifier)
    }
  }

  private def deleteUserItemsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(USER_ITEM_VISIBILITY).
      where(USER_ITEM_VISIBILITY.DOMAIN.equal(pair.domain)).
      and(USER_ITEM_VISIBILITY.PAIR.equal(pair.key)).
      execute()
  }

  private def deleteRepairActionsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(REPAIR_ACTIONS).
      where(REPAIR_ACTIONS.DOMAIN.equal(pair.domain)).
      and(REPAIR_ACTIONS.PAIR_KEY.equal(pair.key)).
      execute()
  }

  private def deleteEscalationsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(ESCALATIONS).
      where(ESCALATIONS.DOMAIN.equal(pair.domain)).
      and(ESCALATIONS.PAIR_KEY.equal(pair.key)).
      execute()
  }

  private def deleteReportsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(PAIR_REPORTS).
      where(PAIR_REPORTS.DOMAIN.equal(pair.domain)).
      and(PAIR_REPORTS.PAIR_KEY.equal(pair.key)).
      execute()
  }

  private def deletePairViewsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(PAIR_VIEWS).
      where(PAIR_VIEWS.DOMAIN.equal(pair.domain)).
      and(PAIR_VIEWS.PAIR.equal(pair.key)).
      execute()
  }

  private def deleteStoreCheckpointsByPair(t:Factory, pair:DiffaPairRef) = {
    t.delete(STORE_CHECKPOINTS).
      where(STORE_CHECKPOINTS.DOMAIN.equal(pair.domain)).
      and(STORE_CHECKPOINTS.PAIR.equal(pair.key)).
      execute()
  }

  def makeDomainMember(domain:String, userName:String) = {

    jooq.execute(t => {
      t.insertInto(MEMBERS).
        set(MEMBERS.DOMAIN_NAME, domain).
        set(MEMBERS.USER_NAME, userName).
        onDuplicateKeyIgnore().
        execute()
    })

    invalidateMembershipCache(domain)

    val member = Member(userName,domain)
    membershipListener.onMembershipCreated(member)
    member
  }

  def removeDomainMembership(domain:String, userName:String) = {

    jooq.execute(t => {
      t.delete(MEMBERS).
        where(MEMBERS.DOMAIN_NAME.equal(domain)).
        and(MEMBERS.USER_NAME.equal(userName)).
        execute()
    })

    invalidateMembershipCache(domain)

    val member = Member(userName,domain)
    membershipListener.onMembershipRemoved(member)
  }

  def listDomainMembers(domain:String) = cachedMembers.readThrough(domain, () => {
    jooq.execute(t => {

      val results = t.select(MEMBERS.USER_NAME).
                     from(MEMBERS).
                     where(MEMBERS.DOMAIN_NAME.equal(domain)).
                     fetch()

      val members = new java.util.ArrayList[Member]()
      results.iterator().foreach(r => members.add(Member(r.getValue(MEMBERS.USER_NAME), domain)))
      members

    })
  }).toSeq

}

// These key classes need to be serializable .......

case class DomainEndpointKey(
  @BeanProperty var domain: String = null,
  @BeanProperty var endpoint: String = null) {

  def this() = this(domain = null)

}

case class DomainPairKey(
  @BeanProperty var domain: String = null,
  @BeanProperty var pair: String = null) {

  def this() = this(domain = null)

}

case class DomainConfigKey(
  @BeanProperty var domain: String = null,
  @BeanProperty var configKey: String = null) {

  def this() = this(domain = null)

}

case class ConfigOptionByDomainPredicate(
  @BeanProperty domain:String = null) extends KeyPredicate[DomainConfigKey] {
  def this() = this(domain = null)
  def constrain(key: DomainConfigKey) = key.domain == domain
}

case class PairByDomainAndEndpointPredicate(
  @BeanProperty domain:String = null,
  @BeanProperty endpoint:String = null) extends KeyPredicate[DomainEndpointKey] {
  def this() = this(domain = null)
  def constrain(key: DomainEndpointKey) = key.domain == domain && key.endpoint == endpoint
}

case class EndpointByDomainPredicate(@BeanProperty domain:String = null) extends KeyPredicate[DomainEndpointKey] {
  def this() = this(domain = null)
  def constrain(key: DomainEndpointKey) = key.domain == domain
}

case class PairByDomainPredicate(@BeanProperty domain:String = null) extends KeyPredicate[DomainPairKey] {
  def this() = this(domain = null)
  def constrain(key: DomainPairKey) = key.domain == domain
}

