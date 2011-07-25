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

package net.lshift.diffa.kernel.differencing

import net.lshift.diffa.kernel.events._
import net.lshift.diffa.kernel.participants._
import net.lshift.diffa.kernel.config.DomainConfigStore
import scala.collection.JavaConversions._
import net.lshift.diffa.participant.scanning.{ScanConstraint, ScanResultEntry}
import net.lshift.diffa.kernel.diag.DiagnosticsManager
import net.lshift.diffa.kernel.config.{Pair => DiffaPair}


/**
 * Version policy where two events are considered the same based on the downstream reporting the same upstream
 * version upon processing. The downstream is not expected to reproduce the same digests as the upstream on demand,
 * and matching recovery will require messages to be reprocessed via a differencing back-channel to determine
 * whether they are identical.
 */
class CorrelatedVersionPolicy(stores:VersionCorrelationStoreFactory,
                              listener:DifferencingListener,
                              configStore:DomainConfigStore,
                              diagnostics:DiagnosticsManager)
    extends BaseScanningVersionPolicy(stores, listener, configStore, diagnostics) {

  def downstreamStrategy(us:UpstreamParticipant, ds:DownstreamParticipant) = new DownstreamCorrelatingScanStrategy(us,ds)
  
  protected class DownstreamCorrelatingScanStrategy(val us:UpstreamParticipant, val ds:DownstreamParticipant)
      extends ScanStrategy {
    
    def getAggregates(pair:DiffaPair, bucketing:Seq[CategoryFunction], constraints:Seq[ScanConstraint]) = {
      val aggregator = new Aggregator(bucketing)
      stores(pair).queryDownstreams(constraints, aggregator.collectDownstream)
      aggregator.digests
    }

    def getEntities(pair:DiffaPair, constraints:Seq[ScanConstraint]) = {
      stores(pair).queryDownstreams(constraints).map(x => {
        ScanResultEntry.forEntity(x.id, x.downstreamDVsn, x.lastUpdate, mapAsJavaMap(x.downstreamAttributes))
      })
    }

    def handleMismatch(pair:DiffaPair, writer: LimitedVersionCorrelationWriter, vm:VersionMismatch, listener:DifferencingListener) = {
      vm match {
        case VersionMismatch(id, categories, _, null, storedVsn) =>
          handleUpdatedCorrelation(writer.clearDownstreamVersion(new VersionID(pair, id)))
        case VersionMismatch(id, categories, lastUpdated, partVsn, _) =>
          val content = us.retrieveContent(id)
          val response = ds.generateVersion(content)

          if (response.getDvsn == partVsn) {
            // This is the same destination object, so we're safe to store the correlation
            handleUpdatedCorrelation(writer.storeDownstreamVersion(new VersionID(pair, id), categories, lastUpdated, response.getUvsn, response.getDvsn))
          } else {
            // We can't update our datastore, so we just have to generate a mismatch
            listener.onMismatch(new VersionID(pair, id), lastUpdated, response.getDvsn, partVsn, TriggeredByScan)
          }
      }
    }
  }
}