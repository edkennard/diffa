/**
 * Copyright (C) 2010-2012 LShift Ltd.
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

import org.jooq.impl.Factory
import net.lshift.diffa.schema.tables.UniqueCategoryNames.UNIQUE_CATEGORY_NAMES
import net.lshift.diffa.schema.tables.UniqueCategoryViewNames.UNIQUE_CATEGORY_VIEW_NAMES
import net.lshift.diffa.schema.tables.PrefixCategories.PREFIX_CATEGORIES
import net.lshift.diffa.schema.tables.PrefixCategoryViews.PREFIX_CATEGORY_VIEWS
import net.lshift.diffa.schema.tables.SetCategories.SET_CATEGORIES
import net.lshift.diffa.schema.tables.SetCategoryViews.SET_CATEGORY_VIEWS
import net.lshift.diffa.schema.tables.RangeCategories.RANGE_CATEGORIES
import net.lshift.diffa.schema.tables.RangeCategoryViews.RANGE_CATEGORY_VIEWS
import net.lshift.diffa.schema.tables.EndpointViewRollingWindows.ENDPOINT_VIEW_ROLLING_WINDOWS
import scala.collection.JavaConversions._
import net.lshift.diffa.schema.tables.Escalations.ESCALATIONS
import net.lshift.diffa.schema.tables.EscalationRules.ESCALATION_RULES
import net.lshift.diffa.schema.tables.PairReports.PAIR_REPORTS
import net.lshift.diffa.schema.tables.RepairActions.REPAIR_ACTIONS
import net.lshift.diffa.schema.tables.Pairs.PAIRS
import net.lshift.diffa.schema.tables.Spaces.SPACES
import net.lshift.diffa.schema.tables.Endpoints.ENDPOINTS
import net.lshift.diffa.kernel.util.MissingObjectException
import net.lshift.diffa.schema.tables.UserItemVisibility.USER_ITEM_VISIBILITY
import net.lshift.diffa.schema.tables.PairViews.PAIR_VIEWS
import net.lshift.diffa.schema.tables.StoreCheckpoints.STORE_CHECKPOINTS
import net.lshift.diffa.schema.tables.Breakers.BREAKERS
import net.lshift.diffa.schema.jooq.DatabaseFacade
import net.lshift.diffa.schema.tables.EndpointViews._
import collection.mutable
import org.slf4j.LoggerFactory
import org.jooq.exception.DataAccessException
import java.sql.SQLIntegrityConstraintViolationException
import net.lshift.diffa.kernel.util.AlertCodes._
import org.jooq._
import java.lang.{Long => LONG}
import net.lshift.diffa.schema.tables.SpacePaths._
import net.lshift.diffa.kernel.frontend.DomainPairDef
import net.lshift.diffa.kernel.frontend.EndpointDef
import net.lshift.diffa.kernel.frontend.RepairActionDef
import scala.Some
import net.lshift.diffa.kernel.frontend.EndpointViewDef
import net.lshift.diffa.kernel.frontend.PairReportDef
import net.lshift.diffa.kernel.frontend.EscalationDef
import net.lshift.diffa.kernel.frontend.DomainEndpointDef
import net.lshift.diffa.kernel.frontend.PairViewDef

/**
 * This object is a workaround for the fact that Scala is so slow
 */
object JooqConfigStoreCompanion {

  val log = LoggerFactory.getLogger(getClass)

  /**
   * Endpoints do not have filters such as rolling windows; they only have aggregating categories.
   * The endpoint part of the view still needs the same arity, so these two column names are place-holders
   * in the endpoint part of the query.
   */
  val PERIOD_COLUMN = "rw_period"
  val OFFSET_COLUMN = "rw_offset"


  /**
   * Common name for the name of the view across both halves of the union query to list endpoints.
   * In the top half of the union, this column will be null, since that half only deals with endpoints.
   * In the bottom half of the union, this column will contain the name of the endpoint view.
   */
  val VIEW_NAME_COLUMN = UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME.getName

  /**
   * Due to the fact that we need to order the grand union rather than just the individual subselects,
   * we need to select from the grand union. When doing so, the column names called NAME will clash,
   * so we alias the UNIQUE_CATEGORY_NAMES.NAME and UNIQUE_CATEGORY_VIEW_NAMES.NAME fields to something other than NAME.
   */
  val UNIQUE_CATEGORY_ALIAS = "unique_category_alias"
  val SPACE_NAME_ALIAS = "space_name_alias"

  def listEndpoints(jooq:DatabaseFacade, space:Option[Long] = None, endpoint:Option[String] = None) : java.util.List[DomainEndpointDef] = {
    jooq.execute(t => {
      val topHalf =     t.select(UNIQUE_CATEGORY_NAMES.NAME.as(UNIQUE_CATEGORY_ALIAS)).
        select(ENDPOINTS.getFields).
        select(Factory.field("null").as(VIEW_NAME_COLUMN)).
        select(RANGE_CATEGORIES.DATA_TYPE, RANGE_CATEGORIES.LOWER_BOUND, RANGE_CATEGORIES.UPPER_BOUND, RANGE_CATEGORIES.MAX_GRANULARITY).
        select(PREFIX_CATEGORIES.STEP, PREFIX_CATEGORIES.PREFIX_LENGTH, PREFIX_CATEGORIES.MAX_LENGTH).
        select(SET_CATEGORIES.VALUE).
        select(Factory.field("null").as(PERIOD_COLUMN)).
        select(Factory.field("null").as(OFFSET_COLUMN)).
        select(SPACES.NAME.as(SPACE_NAME_ALIAS)).
        from(ENDPOINTS).

        join(SPACES).
          on(SPACES.ID.equal(ENDPOINTS.SPACE)).

        leftOuterJoin(UNIQUE_CATEGORY_NAMES).
          on(UNIQUE_CATEGORY_NAMES.ENDPOINT.equal(ENDPOINTS.ID)).

        leftOuterJoin(RANGE_CATEGORIES).
          on(RANGE_CATEGORIES.ENDPOINT.equal(ENDPOINTS.ID)).
          and(RANGE_CATEGORIES.NAME.equal(UNIQUE_CATEGORY_NAMES.NAME)).

        leftOuterJoin(PREFIX_CATEGORIES).
          on(PREFIX_CATEGORIES.ENDPOINT.equal(ENDPOINTS.ID)).
          and(PREFIX_CATEGORIES.NAME.equal(UNIQUE_CATEGORY_NAMES.NAME)).

        leftOuterJoin(SET_CATEGORIES).
          on(SET_CATEGORIES.ENDPOINT.equal(ENDPOINTS.ID)).
          and(SET_CATEGORIES.NAME.equal(UNIQUE_CATEGORY_NAMES.NAME))

      val firstUnionPart = space match {
        case None    => topHalf
        case Some(s) =>
          val maybeUnionPart = topHalf.where(ENDPOINTS.SPACE.equal(s))
          endpoint match {
            case None    => maybeUnionPart
            case Some(e) => maybeUnionPart.and(ENDPOINTS.NAME.equal(e))
          }
      }

      val bottomHalf =  t.select(UNIQUE_CATEGORY_VIEW_NAMES.NAME.as(UNIQUE_CATEGORY_ALIAS)).
        select(ENDPOINTS.getFields).
        select(ENDPOINT_VIEWS.NAME.as(VIEW_NAME_COLUMN)).
        select(RANGE_CATEGORY_VIEWS.DATA_TYPE, RANGE_CATEGORY_VIEWS.LOWER_BOUND, RANGE_CATEGORY_VIEWS.UPPER_BOUND, RANGE_CATEGORY_VIEWS.MAX_GRANULARITY).
        select(PREFIX_CATEGORY_VIEWS.STEP, PREFIX_CATEGORY_VIEWS.PREFIX_LENGTH, PREFIX_CATEGORY_VIEWS.MAX_LENGTH).
        select(SET_CATEGORY_VIEWS.VALUE).
        select(ENDPOINT_VIEW_ROLLING_WINDOWS.PERIOD).
        select(ENDPOINT_VIEW_ROLLING_WINDOWS.OFFSET).
        select(SPACES.NAME.as(SPACE_NAME_ALIAS)).
        from(ENDPOINT_VIEWS).

        join(ENDPOINTS).
          on(ENDPOINT_VIEWS.ENDPOINT.equal(ENDPOINTS.ID)).

        join(SPACES).
        on(SPACES.ID.equal(ENDPOINTS.SPACE)).

        leftOuterJoin(UNIQUE_CATEGORY_VIEW_NAMES).
          on(UNIQUE_CATEGORY_VIEW_NAMES.ENDPOINT.equal(ENDPOINTS.ID)).
          and(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME.equal(ENDPOINT_VIEWS.NAME)).

        leftOuterJoin(RANGE_CATEGORY_VIEWS).
          on(RANGE_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINTS.ID)).
          and(RANGE_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINT_VIEWS.ENDPOINT)).
          and(RANGE_CATEGORY_VIEWS.NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.NAME)).
          and(RANGE_CATEGORY_VIEWS.VIEW_NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME)).

        leftOuterJoin(PREFIX_CATEGORY_VIEWS).
          on(PREFIX_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINTS.ID)).
          and(PREFIX_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINT_VIEWS.ENDPOINT)).
          and(PREFIX_CATEGORY_VIEWS.NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.NAME)).
          and(PREFIX_CATEGORY_VIEWS.VIEW_NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME)).

        leftOuterJoin(SET_CATEGORY_VIEWS).
          on(SET_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINTS.ID)).
          and(SET_CATEGORY_VIEWS.ENDPOINT.equal(ENDPOINT_VIEWS.ENDPOINT)).
          and(SET_CATEGORY_VIEWS.NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.NAME)).
          and(SET_CATEGORY_VIEWS.VIEW_NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME)).

        leftOuterJoin(ENDPOINT_VIEW_ROLLING_WINDOWS).
          on(ENDPOINT_VIEW_ROLLING_WINDOWS.ENDPOINT.equal(ENDPOINTS.ID)).
          and(ENDPOINT_VIEW_ROLLING_WINDOWS.ENDPOINT.equal(ENDPOINT_VIEWS.ENDPOINT)).
          and(ENDPOINT_VIEW_ROLLING_WINDOWS.NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.NAME)).
          and(ENDPOINT_VIEW_ROLLING_WINDOWS.VIEW_NAME.equal(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME))

      val secondUnionPart = space match {
        case None    => bottomHalf
        case Some(s) =>
          val maybeUnionPart = bottomHalf.where(ENDPOINTS.SPACE.equal(s))
          endpoint match {
            case None    => maybeUnionPart
            case Some(e) => maybeUnionPart.and(ENDPOINTS.NAME.equal(e))
          }
      }

      // Sort the grand union rather than the individual constituent sub-selects

      val grandUnion = firstUnionPart.union(secondUnionPart)

      val results = t.select(grandUnion.getFields).
                      from(grandUnion).
                      orderBy(
                        grandUnion.getField(ENDPOINTS.SPACE),
                        grandUnion.getField(ENDPOINTS.NAME),
                        Factory.field(UNIQUE_CATEGORY_ALIAS)
                      ).
                      fetch()

      val endpoints = new java.util.TreeMap[String,DomainEndpointDef]()

      results.iterator().foreach(record => {

        val currentEndpoint = DomainEndpointDef(
          space = record.getValue(ENDPOINTS.SPACE),
          domain = record.getValue(SPACES.NAME.as(SPACE_NAME_ALIAS)),
          name = record.getValue(ENDPOINTS.NAME),
          scanUrl = record.getValue(ENDPOINTS.SCAN_URL),
          contentRetrievalUrl = record.getValue(ENDPOINTS.CONTENT_RETRIEVAL_URL),
          versionGenerationUrl = record.getValue(ENDPOINTS.VERSION_GENERATION_URL),
          inboundUrl = record.getValue(ENDPOINTS.INBOUND_URL),
          collation = record.getValue(ENDPOINTS.COLLATION_TYPE)
        )

        val compressionKey = currentEndpoint.space + "/" + currentEndpoint.name

        if (!endpoints.contains(compressionKey)) {
          endpoints.put(compressionKey, currentEndpoint)
        }

        val resolvedEndpoint = endpoints.get(compressionKey)

        // Check to see whether this row is for an endpoint view

        val viewName = record.getValueAsString(VIEW_NAME_COLUMN)
        val currentView = if (viewName != null) {
          resolvedEndpoint.views.find(v => v.name == viewName) match {
            case None =>
              // This view has not yet been attached to the endpoint, so attach it now
              val viewToAttach = EndpointViewDef(name = viewName)
              resolvedEndpoint.views.add(viewToAttach)
              Some(viewToAttach)
            case x    => x
          }
        }
        else {
          None
        }

        val categoryName = record.getValueAsString(UNIQUE_CATEGORY_ALIAS)

        def applyCategoryToEndpoint(descriptor: AggregatingCategoryDescriptor) =
          resolvedEndpoint.categories.put(categoryName, descriptor)

        def applyFilterToEndpointView(descriptor: CategoryDescriptor, view: EndpointViewDef) =
          view.categories.put(categoryName, descriptor)

        def applyCategoryToEndpointOrView(descriptor: CategoryDescriptor) = {
          currentView match {
            // Since Map is invariant in its value type parameter, we resort to casting instead of using type bounds.
            case None    => applyCategoryToEndpoint(descriptor.asInstanceOf[AggregatingCategoryDescriptor])
            case Some(v) => applyFilterToEndpointView(descriptor, v)
          }
        }

        def applySetMemberToDescriptorMapForCurrentCategory[T <: CategoryDescriptor](value:String, descriptor: T,
            insertNewSetFn: (SetCategoryDescriptor) => Unit) {
          if (descriptor == null) {
            val setDescriptor = new SetCategoryDescriptor()
            setDescriptor.addValue(value)
            insertNewSetFn(setDescriptor)
          }
          else {
            descriptor.asInstanceOf[SetCategoryDescriptor].addValue(value)
          }
        }

        if (record.getValue(RANGE_CATEGORIES.DATA_TYPE) != null) {
          val dataType = record.getValue(RANGE_CATEGORIES.DATA_TYPE)
          val lowerBound = record.getValue(RANGE_CATEGORIES.LOWER_BOUND)
          val upperBound = record.getValue(RANGE_CATEGORIES.UPPER_BOUND)
          val maxGranularity = record.getValue(RANGE_CATEGORIES.MAX_GRANULARITY)
          val descriptor = new RangeCategoryDescriptor(dataType, lowerBound, upperBound, maxGranularity)
          applyCategoryToEndpointOrView(descriptor)
        }
        else if (record.getValue(PREFIX_CATEGORIES.PREFIX_LENGTH) != null) {
          val prefixLength = record.getValue(PREFIX_CATEGORIES.PREFIX_LENGTH)
          val maxLength = record.getValue(PREFIX_CATEGORIES.MAX_LENGTH)
          val step = record.getValue(PREFIX_CATEGORIES.STEP)
          val descriptor = new PrefixCategoryDescriptor(prefixLength, maxLength, step)
          applyCategoryToEndpointOrView(descriptor)
        }
        else if (record.getValue(SET_CATEGORIES.VALUE) != null) {

          // Set values are a little trickier, since the values for one descriptor are split up over multiple rows

          val setCategoryValue = record.getValue(SET_CATEGORIES.VALUE)
          currentView match {
            case None    =>
              applySetMemberToDescriptorMapForCurrentCategory(setCategoryValue,
                resolvedEndpoint.categories.get(categoryName),
                setCat => resolvedEndpoint.categories.put(categoryName, setCat))
            case Some(v) =>
              applySetMemberToDescriptorMapForCurrentCategory(setCategoryValue,
                v.categories.get(categoryName),
                setCat => v.categories.put(categoryName, setCat))
          }
        } else if (record.getValueAsString(PERIOD_COLUMN) != null) {
          val period = record.getValueAsString(PERIOD_COLUMN)
          val offset = record.getValueAsString(OFFSET_COLUMN)
          val filter = new RollingWindowFilter(period, offset)
          applyFilterToEndpointView(filter, currentView.get)
        }
      })

      new java.util.ArrayList[DomainEndpointDef](endpoints.values())
    })
  }

  def listPairs(jooq:DatabaseFacade,
                space:Long,
                key:Option[String] = None,
                endpoint:Option[String] = None) : Seq[DomainPairDef] = jooq.execute(t => listPairsInCurrentTx(t, space, key, endpoint))

  def listPairsInCurrentTx(t:Factory,
                           space:Long,
                           key:Option[String] = None,
                           endpoint:Option[String] = None) : Seq[DomainPairDef] = {

    val upstream = ENDPOINTS.as("upstream")
    val downstream = ENDPOINTS.as("downstream")

    val baseQuery = t.select(PAIRS.getFields).
      select(PAIR_VIEWS.NAME, PAIR_VIEWS.SCAN_CRON_SPEC, PAIR_VIEWS.SCAN_CRON_ENABLED).
      select(REPAIR_ACTIONS.getFields).
      select(ESCALATIONS.getFields).
      select(ESCALATION_RULES.RULE).
      select(PAIR_REPORTS.getFields).
      select(SPACES.NAME).
      select(upstream.NAME).
      select(downstream.NAME).
      from(PAIRS).
      join(SPACES).
        on(SPACES.ID.equal(PAIRS.SPACE)).
      leftOuterJoin(upstream).
        on(PAIRS.UPSTREAM.equal(upstream.ID)).
      leftOuterJoin(downstream).
        on(PAIRS.DOWNSTREAM.equal(downstream.ID)).
      leftOuterJoin(PAIR_VIEWS).
        on(PAIR_VIEWS.PAIR.equal(PAIRS.NAME)).
        and(PAIR_VIEWS.SPACE.equal(PAIRS.SPACE)).
      leftOuterJoin(REPAIR_ACTIONS).
        on(REPAIR_ACTIONS.PAIR.equal(PAIRS.NAME)).
        and(REPAIR_ACTIONS.SPACE.equal(PAIRS.SPACE)).
      leftOuterJoin(ESCALATIONS).
        on(ESCALATIONS.EXTENT.equal(PAIRS.EXTENT)).
      leftOuterJoin(ESCALATION_RULES).
        on(ESCALATION_RULES.ESCALATION.eq(ESCALATIONS.NAME)).
          and(ESCALATION_RULES.EXTENT.eq(ESCALATIONS.EXTENT)).
      leftOuterJoin(PAIR_REPORTS).
        on(PAIR_REPORTS.PAIR.equal(PAIRS.NAME)).
          and(PAIR_REPORTS.SPACE.equal(PAIRS.SPACE)).
        where(PAIRS.SPACE.equal(space))

    val keyedQuery = key match {
      case None       => baseQuery
      case Some(name) => baseQuery.and(PAIRS.NAME.equal(name))
    }
    val query = endpoint match {
      case None       => keyedQuery
      case Some(name) => keyedQuery.
        and(upstream.NAME.equal(name).
        or(downstream.NAME.equal(name)))
    }

    val results = query.fetch()

    val compressed = new mutable.HashMap[String, DomainPairDef]()
    val knownViews = new mutable.HashSet[String]
    val knownReports = new mutable.HashSet[String]
    val knownRepairs = new mutable.HashSet[String]
    val knownEscalations = new mutable.HashSet[String]

    def compressionKey(pairKey:String) = space + "/" + pairKey
    def onceOnly(record:Record, pairKey:String, field:Field[String], known:mutable.HashSet[String]) = {
      val key = record.getValue(field)
      if (key == null || known.contains(pairKey + "/" + key)) {
        false
      } else {
        known.add(pairKey + "/" + key)
        true
      }
    }

    results.iterator().foreach(record => {
      val pairKey = record.getValue(PAIRS.NAME)
      val compressedKey = compressionKey(pairKey)
      val upstreamName = record.getValue(upstream.NAME)
      val downstreamName = record.getValue(downstream.NAME)
      val pair = compressed.getOrElseUpdate(compressedKey,
        DomainPairDef(
          space = record.getValue(PAIRS.SPACE),
          domain = record.getValue(SPACES.NAME),
          key = record.getValue(PAIRS.NAME),
          upstreamName = upstreamName,
          downstreamName = downstreamName,
          versionPolicyName = record.getValue(PAIRS.VERSION_POLICY_NAME),
          scanCronSpec = record.getValue(PAIRS.SCAN_CRON_SPEC),
          scanCronEnabled = record.getValue(PAIRS.SCAN_CRON_ENABLED),
          matchingTimeout = record.getValue(PAIRS.MATCHING_TIMEOUT),
          allowManualScans = record.getValue(PAIRS.ALLOW_MANUAL_SCANS),
          views = new java.util.ArrayList[PairViewDef]()
        )
      )

      if (onceOnly(record, compressedKey, PAIR_VIEWS.NAME, knownViews)) {
        pair.views.add(PairViewDef(
          name = record.getValue(PAIR_VIEWS.NAME),
          scanCronSpec = record.getValue(PAIR_VIEWS.SCAN_CRON_SPEC),
          scanCronEnabled = record.getValue(PAIR_VIEWS.SCAN_CRON_ENABLED)
        ))
      }

      if (onceOnly(record, compressedKey, REPAIR_ACTIONS.NAME, knownRepairs)) {
        pair.repairActions.add(recordToRepairAction(record))
      }
      if (onceOnly(record, compressedKey, ESCALATIONS.NAME, knownEscalations)) {
        pair.escalations.add(recordToEscalation(record))
      }
      if (onceOnly(record, compressedKey, PAIR_REPORTS.NAME, knownReports)) {
        pair.reports.add(recordToPairReport(record))
      }

      pair

    })

    compressed.values.toList.sortBy(_.key)
  }

  def mapResultsToList[T](results:Result[Record], rowMapper:Record => T) = {
    val escalations = new java.util.ArrayList[T]()
    results.iterator().foreach(r => escalations.add(rowMapper(r)))
    escalations
  }

  def recordToEscalation(record:Record) : EscalationDef = {
    EscalationDef(
      name = record.getValue(ESCALATIONS.NAME),
      action = record.getValue(ESCALATIONS.ACTION),
      actionType = record.getValue(ESCALATIONS.ACTION_TYPE),
      rule = record.getValue(ESCALATION_RULES.RULE),
      delay = record.getValue(ESCALATIONS.DELAY))
  }

  def recordToPairReport(record:Record) : PairReportDef = {
    PairReportDef(
      name = record.getValue(PAIR_REPORTS.NAME),
      target = record.getValue(PAIR_REPORTS.TARGET),
      reportType = record.getValue(PAIR_REPORTS.REPORT_TYPE)
    )
  }

  def recordToRepairAction(record:Record) : RepairActionDef = {
    RepairActionDef(
      name = record.getValue(REPAIR_ACTIONS.NAME),
      scope = record.getValue(REPAIR_ACTIONS.SCOPE),
      url = record.getValue(REPAIR_ACTIONS.URL)
    )
  }

  def deletePairWithDependencies(t:Factory, pair:PairRef) = {
    deleteEscalationsByPair(t, pair)
    deleteRepairActionsByPair(t, pair)
    deleteReportsByPair(t, pair)
    deletePairViewsByPair(t, pair)
    deleteStoreCheckpointsByPair(t, pair)
    deleteUserItemsByPair(t, pair)
    deleteBreakersByPair(t, pair)
    deletePairWithoutDependencies(t, pair)
  }

  private def deletePairWithoutDependencies(t:Factory, pair:PairRef) = {
    val deleted = t.delete(PAIRS).
      where(PAIRS.SPACE.equal(pair.space)).
      and(PAIRS.NAME.equal(pair.name)).
      execute()

    if (deleted == 0) {
      throw new MissingObjectException(pair.identifier)
    }
  }

  def deleteUserItemsByPair(t:Factory, pair:PairRef) = {
    t.delete(USER_ITEM_VISIBILITY).
      where(USER_ITEM_VISIBILITY.SPACE.equal(pair.space)).
      and(USER_ITEM_VISIBILITY.PAIR.equal(pair.name)).
      execute()
  }

  def deleteRecordsInSpace[R <: Record](t: Factory, space: Long, table: Table[R]) = {
    t.delete(table).where(table.getField("ENDPOINT").in(
      t.select(ENDPOINTS.ID).
        from(ENDPOINTS).
        where(ENDPOINTS.SPACE.equal(space))))
  }

  def deleteBreakersByPair(t:Factory, pair:PairRef) = {
    t.delete(BREAKERS).
      where(BREAKERS.SPACE.equal(pair.space)).
        and(BREAKERS.PAIR.equal(pair.name)).
      execute()
  }

  def deleteRepairActionsByPair(t:Factory, pair:PairRef) = {
    t.delete(REPAIR_ACTIONS).
      where(REPAIR_ACTIONS.SPACE.equal(pair.space)).
      and(REPAIR_ACTIONS.PAIR.equal(pair.name)).
      execute()
  }

  def deleteEscalationsByPair(t:Factory, pair:PairRef) = {

    t.update(ESCALATION_RULES).
        set(ESCALATION_RULES.EXTENT, null:LONG).
        set(ESCALATION_RULES.ESCALATION, null:String).
      where(ESCALATION_RULES.EXTENT.eq(
      t.select(PAIRS.EXTENT).
        from(PAIRS).
        where(PAIRS.SPACE.eq(pair.space).
          and(PAIRS.NAME.eq(pair.name)))
      )).
      execute()

    t.delete(ESCALATIONS).
      where(ESCALATIONS.EXTENT.eq(
        t.select(PAIRS.EXTENT).
          from(PAIRS).
          where(PAIRS.SPACE.eq(pair.space).
            and(PAIRS.NAME.eq(pair.name)))
    )).
    execute()

  }

  def deleteReportsByPair(t:Factory, pair:PairRef) = {
    t.delete(PAIR_REPORTS).
      where(PAIR_REPORTS.SPACE.equal(pair.space)).
      and(PAIR_REPORTS.PAIR.equal(pair.name)).
      execute()
  }

  def deletePairViewsByPair(t:Factory, pair:PairRef) = {
    t.delete(PAIR_VIEWS).
      where(PAIR_VIEWS.SPACE.equal(pair.space)).
      and(PAIR_VIEWS.PAIR.equal(pair.name)).
      execute()
  }

  def deleteStoreCheckpointsByPair(t:Factory, pair:PairRef) = {
    t.delete(STORE_CHECKPOINTS).
      where(STORE_CHECKPOINTS.SPACE.equal(pair.space)).
      and(STORE_CHECKPOINTS.PAIR.equal(pair.name)).
      execute()
  }

  def insertCategories(t:Factory,
                       space:java.lang.Long,
                       endpointId:LONG,
                       endpoint:EndpointDef) = {
    endpoint.categories.foreach { case (categoryName, descriptor) => {

      try {
        t.insertInto(UNIQUE_CATEGORY_NAMES).
            set(UNIQUE_CATEGORY_NAMES.ENDPOINT, endpointId).
            set(UNIQUE_CATEGORY_NAMES.NAME, categoryName).
          execute()

        descriptor match {
          case r:RangeCategoryDescriptor  => insertRangeCategory(t, endpointId, categoryName, r)
          case s:SetCategoryDescriptor    => insertSetCategory(t, endpointId, categoryName, s)
          case p:PrefixCategoryDescriptor => insertPrefixCategory(t, endpointId, categoryName, p)
          case rw: RollingWindowFilter    => // Rolling Windows can only be defined on Endpoints.
        }
      }
      catch {
          case e:DataAccessException if e.getCause.isInstanceOf[SQLIntegrityConstraintViolationException] =>
            val msg = "Integrity constraint violation during insert into UNIQUE_CATEGORY_NAMES: domain = %s; endpoint = %s; categories = %s".
                      format(space, endpoint, endpoint.categories)
            log.warn("%s %s".format(formatAlertCode(space, INTEGRITY_CONSTRAINT_VIOLATED), msg))
            log.warn("%s %s".format(formatAlertCode(space, INTEGRITY_CONSTRAINT_VIOLATED), e.getMessage))
            throw e
          case x =>
            log.error("%s Error inserting categories".format(formatAlertCode(space, DB_EXECUTION_ERROR)), x)
            throw x
      }
    }}
  }

  def endpointIdByNameAsField(t: Factory, endpointName: String, space: Long) =
    selectEndpointIdByName(t, endpointName, space).
      asField().
      asInstanceOf[Field[LONG]]

  def selectEndpointIdByName(t: Factory, endpointName: String, spaceId: Long) =
    t.select(ENDPOINTS.ID).
      from(ENDPOINTS).
      where(ENDPOINTS.SPACE.equal(spaceId)).
      and(ENDPOINTS.NAME.equal(endpointName))

  def endpointIdByName(t: Factory, endpointName: String, spaceId: Long) =
    selectEndpointIdByName(t, endpointName, spaceId).fetchOne(ENDPOINTS.ID).longValue():LONG

  def insertCategoriesForView(t:Factory,
                              space:LONG,
                              endpoint:String,
                              endpointId:LONG,
                              view:EndpointViewDef) {
    view.categories.foreach { case (categoryName, descriptor) => {

      try {

        t.insertInto(UNIQUE_CATEGORY_VIEW_NAMES).
            set(UNIQUE_CATEGORY_VIEW_NAMES.ENDPOINT, endpointId).
            set(UNIQUE_CATEGORY_VIEW_NAMES.VIEW_NAME, view.name).
            set(UNIQUE_CATEGORY_VIEW_NAMES.NAME, categoryName).
          execute()

        descriptor match {
          case r:RangeCategoryDescriptor  => insertRangeCategoryView(t, endpointId, view.name, categoryName, r)
          case s:SetCategoryDescriptor    => insertSetCategoryView(t, endpointId, view.name, categoryName, s)
          case p:PrefixCategoryDescriptor => insertPrefixCategoryView(t, endpointId, view.name, categoryName, p)
          case rw: RollingWindowFilter => insertViewRollingWindow(t, endpointId, view.name, categoryName, rw)
        }
      }
      catch {
        case e:DataAccessException if e.getCause.isInstanceOf[SQLIntegrityConstraintViolationException] =>
          val msg = "Integrity constraint violation during insert into UNIQUE_CATEGORY_VIEW_NAMES: domain = %s; endpoint = %s; view = %s".
            format(space, endpoint, view)
          log.warn("%s %s".format(formatAlertCode(space, INTEGRITY_CONSTRAINT_VIOLATED), msg))
          log.warn("%s %s".format(formatAlertCode(space, INTEGRITY_CONSTRAINT_VIOLATED), e.getMessage))
          throw e
        case x =>
          log.error("%s Error inserting view categories".format(formatAlertCode(space, DB_EXECUTION_ERROR)), x)
          throw x
      }
    }}
  }

  def insertPrefixCategory(t:Factory,
                           endpointId:LONG,
                           categoryName:String,
                           descriptor:PrefixCategoryDescriptor) = {

    t.insertInto(PREFIX_CATEGORIES).
        set(PREFIX_CATEGORIES.ENDPOINT, endpointId).
        set(PREFIX_CATEGORIES.NAME, categoryName).
        set(PREFIX_CATEGORIES.STEP, Integer.valueOf(descriptor.step)).
        set(PREFIX_CATEGORIES.MAX_LENGTH, Integer.valueOf(descriptor.maxLength)).
        set(PREFIX_CATEGORIES.PREFIX_LENGTH, Integer.valueOf(descriptor.prefixLength)).
      execute()
  }

  def insertPrefixCategoryView(t:Factory,
                               endpointId:LONG,
                               view:String,
                               categoryName:String,
                               descriptor:PrefixCategoryDescriptor) = {

    t.insertInto(PREFIX_CATEGORY_VIEWS).
      set(PREFIX_CATEGORY_VIEWS.ENDPOINT, endpointId).
      set(PREFIX_CATEGORY_VIEWS.VIEW_NAME, view).
      set(PREFIX_CATEGORY_VIEWS.NAME, categoryName).
      set(PREFIX_CATEGORY_VIEWS.STEP, Integer.valueOf(descriptor.step)).
      set(PREFIX_CATEGORY_VIEWS.MAX_LENGTH, Integer.valueOf(descriptor.maxLength)).
      set(PREFIX_CATEGORY_VIEWS.PREFIX_LENGTH, Integer.valueOf(descriptor.prefixLength)).
      execute()
  }

  def insertSetCategory(t:Factory,
                        endpointId:LONG,
                        categoryName:String,
                        descriptor:SetCategoryDescriptor) = {

    // TODO Is there a way to re-use the insert statement with a bind parameter?

    descriptor.values.foreach(value => {
      t.insertInto(SET_CATEGORIES).
        set(SET_CATEGORIES.ENDPOINT, endpointId).
        set(SET_CATEGORIES.NAME, categoryName).
        set(SET_CATEGORIES.VALUE, value).
      execute()
    })
  }

  def insertSetCategoryView(t:Factory,
                            endpointId:LONG,
                            view:String,
                            categoryName:String,
                            descriptor:SetCategoryDescriptor) {

    // TODO Is there a way to re-use the insert statement with a bind parameter?

    descriptor.values.foreach(value => {
      t.insertInto(SET_CATEGORY_VIEWS).
        set(SET_CATEGORY_VIEWS.ENDPOINT, endpointId).
        set(SET_CATEGORY_VIEWS.VIEW_NAME, view).
        set(SET_CATEGORY_VIEWS.NAME, categoryName).
        set(SET_CATEGORY_VIEWS.VALUE, value).
        execute()
    })
  }

  def insertRangeCategory(t:Factory,
                          endpointId:LONG,
                          categoryName:String,
                          descriptor:RangeCategoryDescriptor) = {
    t.insertInto(RANGE_CATEGORIES).
        set(RANGE_CATEGORIES.ENDPOINT, endpointId).
        set(RANGE_CATEGORIES.NAME, categoryName).
        set(RANGE_CATEGORIES.DATA_TYPE, descriptor.dataType).
        set(RANGE_CATEGORIES.LOWER_BOUND, descriptor.lower).
        set(RANGE_CATEGORIES.UPPER_BOUND, descriptor.upper).
        set(RANGE_CATEGORIES.MAX_GRANULARITY, descriptor.maxGranularity).
      execute()
  }

  def insertViewRollingWindow(t: Factory,
                          endpointId: LONG,
                          viewName: String,
                          categoryName: String,
                          filter: RollingWindowFilter) =
    t.insertInto(ENDPOINT_VIEW_ROLLING_WINDOWS).
        set(ENDPOINT_VIEW_ROLLING_WINDOWS.ENDPOINT, endpointId).
        set(ENDPOINT_VIEW_ROLLING_WINDOWS.VIEW_NAME, viewName).
        set(ENDPOINT_VIEW_ROLLING_WINDOWS.NAME, categoryName).
        set(ENDPOINT_VIEW_ROLLING_WINDOWS.PERIOD, filter.periodExpression).
        set(ENDPOINT_VIEW_ROLLING_WINDOWS.OFFSET, filter.offsetDurationExpression).
      execute()


  def insertRangeCategoryView(t:Factory,
                              endpointId:LONG,
                              view:String,
                              categoryName:String,
                              descriptor:RangeCategoryDescriptor) {
    t.insertInto(RANGE_CATEGORY_VIEWS).
      set(RANGE_CATEGORY_VIEWS.ENDPOINT, endpointId).
      set(RANGE_CATEGORY_VIEWS.VIEW_NAME, view).
      set(RANGE_CATEGORY_VIEWS.NAME, categoryName).
      set(RANGE_CATEGORY_VIEWS.DATA_TYPE, descriptor.dataType).
      set(RANGE_CATEGORY_VIEWS.LOWER_BOUND, descriptor.lower).
      set(RANGE_CATEGORY_VIEWS.UPPER_BOUND, descriptor.upper).
      set(RANGE_CATEGORY_VIEWS.MAX_GRANULARITY, descriptor.maxGranularity).
      execute()
  }

  def deleteGenericCategoriesForEndpoint[R <: Record](t: Factory, space: Long, endpoint: String, table: Table[R]) =
    t.delete(table).where(table.getField("ENDPOINT").in(selectEndpointIdByName(t, endpoint, space)))

  def deleteCategories(t:Factory, space:Long, endpoint:String) = {
    // The order of these tables is important. Incorrect ordering will result in foreign key constraint violation.
    val categoryTables = Seq(
      PREFIX_CATEGORY_VIEWS,
      PREFIX_CATEGORIES,
      SET_CATEGORY_VIEWS,
      SET_CATEGORIES,
      ENDPOINT_VIEW_ROLLING_WINDOWS,
      RANGE_CATEGORY_VIEWS,
      RANGE_CATEGORIES,
      UNIQUE_CATEGORY_VIEW_NAMES,
      UNIQUE_CATEGORY_NAMES)

    categoryTables foreach (deleteGenericCategoriesForEndpoint(t, space, endpoint, _).execute())
  }

  def ancestorIdTree(t:Factory, space:Long):Seq[Long] = {
    val hierarchy = t.select().
      from(SPACE_PATHS).
      where(SPACE_PATHS.DESCENDANT.equal(space).and(SPACE_PATHS.ANCESTOR.notEqual(space))).
      orderBy(SPACE_PATHS.DEPTH.asc()).
      fetch()

    if (hierarchy == null) {
      throw new MissingObjectException(space.toString)
    }
    else {
      hierarchy.iterator().map(_.getValue(SPACE_PATHS.ANCESTOR).longValue()).toSeq
    }
  }
}
