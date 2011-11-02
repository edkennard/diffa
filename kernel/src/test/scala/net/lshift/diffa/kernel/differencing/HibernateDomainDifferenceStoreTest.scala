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

import org.hibernate.cfg.Configuration
import org.hibernate.exception.ConstraintViolationException
import org.junit.Assert._
import net.lshift.diffa.kernel.config._
import net.lshift.diffa.kernel.events.VersionID
import net.lshift.diffa.kernel.frontend.{EndpointDef, PairDef}
import org.junit._
import experimental.theories.{Theories, DataPoint, Theory}
import org.hibernate.SessionFactory
import runner.RunWith
import system.HibernateSystemConfigStore
import net.lshift.diffa.kernel.differencing.HibernateDomainDifferenceStoreTest.TileScenario
import scala.collection.JavaConversions._
import net.lshift.diffa.kernel.differencing.ZoomCache._
import scala.collection.mutable.HashMap
import net.sf.ehcache.CacheManager
import net.lshift.diffa.kernel.util.DatabaseEnvironment
import org.joda.time.{DateTime, Interval, DateTimeZone}
import org.hibernate.dialect.Dialect

/**
 * Test cases for the HibernateDomainDifferenceStore.
 */
@RunWith(classOf[Theories])
class HibernateDomainDifferenceStoreTest {
  import HibernateDomainDifferenceStoreTest._

  @Before
  def clear() {
    diffStore.clearAllDifferences

    val pairCache = new PairCache(new CacheManager())
    val configStore = new HibernateDomainConfigStore(sf, pairCache)
    val systemConfigStore = new HibernateSystemConfigStore(sf, pairCache)

    val domain = Domain("domain")
    systemConfigStore.createOrUpdateDomain(domain)
    val us = EndpointDef(name = "upstream", contentType = "application/json")
    val ds = EndpointDef(name = "downstream", contentType = "application/json")
    configStore.createOrUpdateEndpoint(domain.name, us)
    configStore.createOrUpdateEndpoint(domain.name, ds)

    val pairTemplate = PairDef(upstreamName = us.name, downstreamName = ds.name)
    val pair1 = pairTemplate.copy(key = "pair1")
    val pair2 = pairTemplate.copy(key = "pair2")

    configStore.listPairs(domain.name).foreach(p => configStore.deletePair(domain.name, p.key))
    configStore.createOrUpdatePair(domain.name, pair1)
    configStore.createOrUpdatePair(domain.name, pair2)
  }

  @Test
  def shouldNotPublishPendingUnmatchedEventInAllUnmatchedList() {
    val now = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), now, "uV", "dV", now)
    val interval = new Interval(now.minusDays(1), now.plusDays(1))
    assertEquals(0, diffStore.retrieveUnmatchedEvents("domain", interval).length)
  }

  @Test
  def shouldPublishUpgradedUnmatchedEventInAllUnmatchedList() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV", "dV", timestamp)
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(1, unmatched.length)
    assertEquals(VersionID(DiffaPairRef("pair1",  "domain"), "id1"), unmatched.head.objId)
    assertEquals(timestamp, unmatched.head.detectedAt)
    assertEquals("uV", unmatched.head.upstreamVsn)
    assertEquals("dV", unmatched.head.downstreamVsn)
  }

  @Test
  def shouldIgnoreUpgradeRequestsForUnknownIDs() {
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))
    val interval = new Interval(new DateTime(), new DateTime())
    assertEquals(0, diffStore.retrieveUnmatchedEvents("domain", interval).length)
  }

  @Test
  def shouldOverwritePendingEventsWhenNewPendingEventsArrive() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV", "dV", timestamp)
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV2", "dV2", timestamp)
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))

    // Even if there were multiple pending registrations, we should only see one unmatched event when we upgrade, and
    // it should use the details of the final pending event.
    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(1, unmatched.length)
    assertEquals(VersionID(DiffaPairRef("pair1",  "domain"), "id1"), unmatched.head.objId)
    assertEquals(timestamp, unmatched.head.detectedAt)
    assertEquals("uV2", unmatched.head.upstreamVsn)
    assertEquals("dV2", unmatched.head.downstreamVsn)
  }

  @Test
  def shouldIgnoreUpgradeRequestWhenPendingEventHasBeenUpgradedAlready() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV", "dV", timestamp)
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    assertEquals(1, diffStore.retrieveUnmatchedEvents("domain", interval).length)
  }

  @Test
  def shouldIgnoreUpgradeRequestWhenPendingEventHasBeenCancelled() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV", "dV", timestamp)
    assertTrue(diffStore.cancelPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), "uV"))
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    assertEquals(0, diffStore.retrieveUnmatchedEvents("domain", interval).length)
  }

  @Test
  def shouldNotCancelPendingEventWhenProvidedVersionIsDifferent() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), timestamp, "uV", "dV", timestamp)
    assertFalse(diffStore.cancelPendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"), "uV-different"))
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair1", "domain"), "id1"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    assertEquals(1, diffStore.retrieveUnmatchedEvents("domain", interval).length)
  }

  @Test
  def shouldPublishAnAddedReportableUnmatchedEvent() {
    val timestamp = new DateTime()
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(1, unmatched.length)
    assertEquals(MatchState.UNMATCHED, unmatched.head.state)
    assertEquals(VersionID(DiffaPairRef("pair2",  "domain"), "id2"), unmatched.head.objId)
    assertEquals(timestamp, unmatched.head.detectedAt)
    assertEquals("uV", unmatched.head.upstreamVsn)
    assertEquals("dV", unmatched.head.downstreamVsn)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def shouldNotBeAbleToIgnoreDifferenceViaWrongDomain() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.ignoreEvent("domain2", evt.seqId)
  }

  @Test
  def shouldNotPublishAnIgnoredReportableUnmatchedEventInRetrieveUnmatchedEventsQuery() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.ignoreEvent("domain", evt.seqId)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(0, unmatched.length)
  }

  @Test
  def shouldUpdatePreviouslyIgnoredReportableUnmatchedEvent() {
    val timestamp = new DateTime()
    val event1 = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV1", "dV1", timestamp)
    diffStore.ignoreEvent("domain", event1.seqId)
    val event2 = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV2", "dV1", timestamp)
    assertTrue(event1.seqId.toInt < event2.seqId.toInt)
  }

  @Test
  def shouldRemoveReportedEventsById() {
    val timestamp = new DateTime()
    val pair = DiffaPairRef("pair2", "domain")

    def versionId(i:Int) = VersionID(pair, "id-" + i)

    val events = 100

    for (i <- 0 until events) {
      diffStore.addReportableUnmatchedEvent(versionId(i), timestamp, "uV-" + i, "dV-" + i, timestamp)
    }

    def checkUnmatched(expectation:Int) = {
      val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
      val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
      assertEquals(expectation, unmatched.length)
    }

    checkUnmatched(events)

    val toDelete = for (i <- 0 until (events / 2)) yield versionId(i)
    diffStore.removeEvents(toDelete)

    checkUnmatched(events / 2)

  }

  @Test
  def shouldReportUnmatchedEventWithinInterval() {
    val start = new DateTime(2004, 11, 6, 3, 5, 15, 0)
    val size = 60
    var frontFence = 10
    var rearFence = 10

    val interval = addUnmatchedEvents(start, size, frontFence, rearFence)

    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(size - frontFence - rearFence, unmatched.length)
  }

  @Test
  def shouldCountUnmatchedEventWithinInterval() {
    val start = new DateTime(2004, 11, 6, 3, 5, 15, 0)
    val size = 60
    var frontFence = 10
    var rearFence = 10

    val interval = addUnmatchedEvents(start, size, frontFence, rearFence)

    val unmatchedCount = diffStore.countUnmatchedEvents(DiffaPairRef("pair2", "domain"), interval)
    assertEquals(size - frontFence - rearFence, unmatchedCount)
  }

  @Test
  def shouldNotCountMatchedEvents() {
    val timestamp = new DateTime

    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), "uV")

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatchedCount = diffStore.countUnmatchedEvents(DiffaPairRef("pair2", "domain"), interval)
    assertEquals(0, unmatchedCount)
  }

  @Test
  def matchedEventShouldCancelPreviouslyIgnoredEvent() {
    val timestamp = new DateTime
    val id = VersionID(DiffaPairRef("pair2", "domain"), "id2")

    val event1 = diffStore.addReportableUnmatchedEvent(id, timestamp, "uV", "dV", timestamp)
    val event2 = diffStore.ignoreEvent("domain", event1.seqId)
    assertMatchState("domain", event2.seqId, MatchState.IGNORED)

    val event3 = diffStore.addMatchedEvent(id, "vsn")
    assertMatchState("domain", event3.seqId, MatchState.MATCHED)
  }

  private def assertMatchState(domain:String, sequenceId:String, state:MatchState) = {
    val event = diffStore.getEvent(domain, sequenceId)
    assertEquals(state, event.state)
  }

  @Test
  def shouldNotCountIgnoredEvents() {
    val timestamp = new DateTime

    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.ignoreEvent("domain", evt.seqId)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatchedCount = diffStore.countUnmatchedEvents(DiffaPairRef("pair2", "domain"), interval)
    assertEquals(0, unmatchedCount)
  }

  def addUnmatchedEvents(start:DateTime, size:Int, frontFence:Int, rearFence:Int) : Interval = {
    for (i <- 1 to size) {
      val timestamp = start.plusMinutes(i)
      diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id" + i), timestamp, "uV", "dV", timestamp)
    }
    new Interval(start.plusMinutes(frontFence), start.plusMinutes(size - rearFence))
  }

  @Test
  def shouldPageReportableUnmatchedEvent() {
    val start = new DateTime(1982, 5, 5, 14, 15, 19, 0)
    val size = 100
    var frontFence = 20
    var rearFence = 50

    // Set a bound so that 30 events fall into the window
    val interval = addUnmatchedEvents(start, size, frontFence, rearFence)

    // Create an interval that is wide enough to get every event ever
    val veryWideInterval = new Interval(start.minusDays(1), start.plusDays(1))

    val unmatched = diffStore.retrieveUnmatchedEvents("domain", veryWideInterval)
    assertEquals(size, unmatched.length)

    // Requesting 19 elements with an offset of 10 from 30 elements should yield elements 10 through to 28
    val containedPage = diffStore.retrievePagedEvents(DiffaPairRef("pair2", "domain"), interval, 10, 19)
    assertEquals(19, containedPage.length)

    // Requesting 19 elements with an offset of 20 from 30 elements should yield elements 20 through to 29
    val splitPage = diffStore.retrievePagedEvents(DiffaPairRef("pair2", "domain"), interval, 20, 19)
    assertEquals(10, splitPage.length)

  }

  @Test
  def shouldNotPublishAnIgnoredReportableUnmatchedEventInPagedEventQuery() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.ignoreEvent("domain", evt.seqId)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val containedPage = diffStore.retrievePagedEvents(DiffaPairRef("pair2", "domain"), interval, 0, 100)
    assertEquals(0, containedPage.length)
  }

  @Test
  def shouldPublishAnIgnoredReportableUnmatchedEventInPagedEventQueryWhenIgnoredEntitiesAreRequested() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.ignoreEvent("domain", evt.seqId)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val containedPage = diffStore.retrievePagedEvents(DiffaPairRef("pair2", "domain"), interval, 0, 100, EventOptions(includeIgnored = true))

    assertEquals(1, containedPage.length)
    assertEquals(VersionID(DiffaPairRef("pair2", "domain"), "id2"), containedPage(0).objId)
  }

  @Test
  def shouldPublishAnEventThatHasBeenUnignored() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    val ignored = diffStore.ignoreEvent("domain", evt.seqId)
    diffStore.unignoreEvent("domain", ignored.seqId)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val containedPage = diffStore.retrievePagedEvents(DiffaPairRef("pair2", "domain"), interval, 0, 100)

    assertEquals(1, containedPage.length)
    assertEquals(VersionID(DiffaPairRef("pair2", "domain"), "id2"), containedPage(0).objId)
  }

  @Test
  def shouldAddIgnoredEventThatOverridesUnmatchedEventWhenAskingForSequenceUpdate() {
    val timestamp = new DateTime()
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uuV", "ddV", timestamp)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    val lastSeq = unmatched.last.seqId

    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), "uuV")
    val updates = diffStore.retrieveEventsSince("domain", lastSeq)

    assertEquals(1, updates.length)
    assertEquals(MatchState.MATCHED, updates.head.state)
    // We don't know deterministically when the updated timestamp will be because this
    // is timestamped on the fly from within the implementation of the cache
    // but we do want to assert that it is not before the reporting timestamp
    assertFalse(timestamp.isAfter(updates.head.detectedAt))
    assertEquals(VersionID(DiffaPairRef("pair2", "domain"), "id2"), updates.head.objId)
  }

  @Test
  def shouldAddMatchedEventThatOverridesIgnoredEventWhenAskingForSequenceUpdate() {
    val timestamp = new DateTime()
    val newUnmatched = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uuV", "ddV", timestamp)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    val lastSeq = unmatched.last.seqId

    diffStore.ignoreEvent("domain", newUnmatched.seqId)
    val updates = diffStore.retrieveEventsSince("domain", lastSeq)

    assertEquals(1, updates.length)
    assertEquals(MatchState.IGNORED, updates.head.state)  // Match events for ignored differences have a state IGNORED
    // We don't know deterministically when the updated timestamp will be because this
    // is timestamped on the fly from within the implementation of the cache
    // but we do want to assert that it is not before the reporting timestamp
    assertFalse(timestamp.isAfter(updates.head.detectedAt))
    assertEquals(VersionID(DiffaPairRef("pair2", "domain"), "id2"), updates.head.objId)
  }

  @Test
  def shouldRemoveUnmatchedEventFromAllUnmatchedWhenAMatchHasBeenAdded() {
    val timestamp = new DateTime()
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uuV", "ddV", timestamp)
    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), "uuV")
    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val updates = diffStore.retrieveUnmatchedEvents("domain", interval)

    assertEquals(0, updates.length)
  }

  @Test
  def shouldIgnoreMatchedEventWhenNoOverridableUnmatchedEventIsStored() {
    val timestamp = new DateTime()
    // Get an initial event and a sequence number
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    val lastSeq = unmatched.last.seqId

    // Add a matched event for something that we don't have marked as unmatched
    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair3","domain"), "id3"), "eV")
    val updates = diffStore.retrieveEventsSince("domain", lastSeq)
    assertEquals(0, updates.length)
  }

  @Test
  def shouldOverrideOlderUnmatchedEventsWhenNewMismatchesOccurWithDifferentDetails() {
    // Add two events for the same object, and then ensure the old list only includes the most recent one
    val timestamp = new DateTime()
    val seen = new DateTime().plusSeconds(5)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV2", "dV2", seen)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(1, unmatched.length)
    validateUnmatchedEvent(unmatched(0), VersionID(DiffaPairRef("pair2","domain"), "id2"), "uV2", "dV2", timestamp, seen)
  }

  @Test
  def shouldRetainOlderUnmatchedEventsWhenNewEventsAreAddedWithSameDetailsButUpdateTheSeenTime() {
    // Add two events for the same object with all the same details, and ensure that we don't modify the event
    val timestamp = new DateTime()
    val newSeen = new DateTime().plusSeconds(5)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp.plusSeconds(15), "uV", "dV", newSeen)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(1, unmatched.length)
    validateUnmatchedEvent(unmatched(0), VersionID(DiffaPairRef("pair2","domain"), "id2"), "uV", "dV", timestamp, newSeen)
  }

  @Test
  def shouldNotReapMatchedEvents() {
    val timestamp = new DateTime()
    val id = VersionID(DiffaPairRef("pair2","domain"), "id1")

    diffStore.addReportableUnmatchedEvent(id, timestamp, "uV", "dV", timestamp)

    // Match a previously unmatched event and then kick off the event reaping process
    val event = diffStore.addMatchedEvent(id, "vsn")
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), new DateTime())

    // The event reaping process should not have created a new sequence number
    val eventsSince = diffStore.retrieveEventsSince("domain", event.seqId)
    assertTrue(eventsSince.isEmpty)
  }

  @Test
  def shouldRemoveEventsNotSeenAfterTheGivenCutoff() {
    val timestamp = new DateTime()
    val seen1 = timestamp.plusSeconds(5)
    val seen2 = timestamp.plusSeconds(8)
    val cutoff = timestamp.plusSeconds(9)
    val seen3 = timestamp.plusSeconds(10)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV", "dV", seen2)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id3"), timestamp, "uV", "dV", seen3)
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), cutoff)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    validateUnmatchedEvent(unmatched(0), VersionID(DiffaPairRef("pair2","domain"), "id3"), "uV", "dV", timestamp, seen3)
  }

  @Test
  def shouldNotRemoveEventsFromADifferentPair() {
    val timestamp = new DateTime()
    val seen1 = timestamp.plusSeconds(5)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), seen1)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    validateUnmatchedEvent(unmatched(0), VersionID(DiffaPairRef("pair2","domain"), "id1"), "uV", "dV", timestamp, seen1)
  }

  @Test
  def shouldNotRemoveEventsSeenExactlyAtTheGivenCutoff() {
    val timestamp = new DateTime()
    val seen1 = timestamp.plusSeconds(5)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)
    diffStore.matchEventsOlderThan(DiffaPairRef(domain = "domain", key = "pair2"), seen1)

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    validateUnmatchedEvent(unmatched(0), VersionID(DiffaPairRef("pair2","domain"), "id1"), "uV", "dV", timestamp, seen1)
  }

  @Test
  def shouldAddMatchEventsForThoseRemovedByACutoff() {
    val now = new DateTime
    val timestamp = now .minusSeconds(10)
    val seen1 = now .plusSeconds(5)
    val seen2 = now .plusSeconds(8)
    val cutoff = now .plusSeconds(9)
    val seen3 = now .plusSeconds(10)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV", "dV", seen2)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id3"), timestamp, "uV", "dV", seen3)
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), cutoff)

    val events = diffStore.retrieveEventsSince("domain", "0")
    assertEquals(3, events.length)

    validateUnmatchedEvent(events(0), VersionID(DiffaPairRef("pair2","domain"), "id3"), "uV", "dV", timestamp, seen3)
    validateMatchedEvent(events(1), VersionID(DiffaPairRef("pair2","domain"), "id1"), "uV", now)
    validateMatchedEvent(events(2), VersionID(DiffaPairRef("pair2","domain"), "id2"), "uV", now)
  }
  @Test
  def shouldNotRemoveOrDuplicateMatchEventsSeenBeforeTheCutoff() {
    val timestamp = new DateTime()
    val seen1 = timestamp.plusSeconds(5)
    val cutoff = timestamp.plusSeconds(20)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)
    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), "uV")
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), cutoff)

    val events = diffStore.retrieveEventsSince("domain", "0")
    assertEquals(1, events.length)
    validateMatchedEvent(events(0), VersionID(DiffaPairRef("pair2","domain"), "id1"), "uV", timestamp)
  }

  @Test
  def shouldAllowRetrievalOfReportedEvent() {
    val timestamp = new DateTime()
    val evt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)

    val retrieved = diffStore.getEvent("domain", evt.seqId)
    validateUnmatchedEvent(retrieved, evt.objId, evt.upstreamVsn, evt.downstreamVsn, evt.detectedAt, evt.lastSeen)
  }

  @Test
  def shouldThrowExceptionWhenRetrievingNonExistentSeqNumber() {
    try {
      diffStore.getEvent("domain", "55")    // Cache should be empty, so any seqId should be invalid
      fail("Should have thrown InvalidSequenceNumberException")
    } catch {
      case e:InvalidSequenceNumberException => assertEquals("55", e.id)
    }
  }

  @Test(expected = classOf[InvalidSequenceNumberException])
  def shouldThrowExceptionWhenRetrievingEventBySequenceNumberOfRemovedEvent() {
    val timestamp = new DateTime()
    val unmatchedEvt = diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addMatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), "uV")

    diffStore.getEvent("domain", unmatchedEvt.seqId)    // Unmatched event should have been removed when matched event was added
  }

  @Test
  def shouldRemoveEventsWhenDomainIsRemoved() {
    val timestamp = new DateTime()
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id3"), timestamp, "uV", "dV", timestamp)

    diffStore.removeDomain("domain")

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(0, unmatched.length)
  }

  @Test
  def shouldRemovePendingEventsWhenDomainIsRemoved() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)

    diffStore.removeDomain("domain")

    // Upgrade the pending difference we previously created. We shouldn't see any differences, because we should
    // have just submitted an upgrade for a pending event that doesn't exist.
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(0, unmatched.length)
  }

  @Test
  def shouldRemoveMatches() {
    val timestamp = new DateTime()
    val seen1 = timestamp.plusSeconds(5)
    val seen2 = timestamp.plusSeconds(8)
    val cutoff = timestamp.plusSeconds(9)
    val seen3 = timestamp.plusSeconds(10)
    val afterAll = timestamp.plusSeconds(20)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id1"), timestamp, "uV", "dV", seen1)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id2"), timestamp, "uV", "dV", seen2)   // Before the cutoff, will be removed
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2","domain"), "id3"), timestamp, "uV", "dV", seen3)
    diffStore.matchEventsOlderThan(DiffaPairRef("pair2","domain"), afterAll)     // Convert all the events to matches
    diffStore.expireMatches(cutoff)

    val events = diffStore.retrieveEventsSince("domain", "0")
    assertEquals(0, events.length)
  }

  @Test
  def shouldRemoveEventsWhenPairIsRemoved() {
    val timestamp = new DateTime()
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)
    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id3"), timestamp, "uV", "dV", timestamp)

    diffStore.removePair(DiffaPairRef("pair2", "domain"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(0, unmatched.length)
  }

  @Test
  def shouldRemovePendingEventsWhenPairIsRemoved() {
    val timestamp = new DateTime()
    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"), timestamp, "uV", "dV", timestamp)

    diffStore.removePair(DiffaPairRef("pair2", "domain"))

    // Upgrade the pending difference we previously created. We shouldn't see any differences, because we should
    // have just submitted an upgrade for a pending event that doesn't exist.
    diffStore.upgradePendingUnmatchedEvent(VersionID(DiffaPairRef("pair2", "domain"), "id2"))

    val interval = new Interval(timestamp.minusDays(1), timestamp.plusDays(1))
    val unmatched = diffStore.retrieveUnmatchedEvents("domain", interval)
    assertEquals(0, unmatched.length)
  }

  @Test(expected = classOf[ConstraintViolationException])
  def shouldFailToAddReportableEventForNonExistentPair() {
    val lastUpdate = new DateTime()
    val seen = lastUpdate.plusSeconds(5)

    diffStore.addReportableUnmatchedEvent(VersionID(DiffaPairRef("nonexistent-pair1", "domain"), "id1"), lastUpdate, "uV", "dV", seen)
  }

  @Test(expected = classOf[ConstraintViolationException])
  def shouldFailToAddPendingEventForNonExistentPair() {
    val lastUpdate = new DateTime()
    val seen = lastUpdate.plusSeconds(5)

    diffStore.addPendingUnmatchedEvent(VersionID(DiffaPairRef("nonexistent-pair2", "domain"), "id1"), lastUpdate, "uV", "dV", seen)
  }

  @Test
  def shouldStoreLatestVersionPerPair = {
    val pair = DiffaPairRef("pair1", "domain")
    assertEquals(None, diffStore.lastRecordedVersion(pair))
    diffStore.recordLatestVersion(pair, 5294967296L)
    assertEquals(Some(5294967296L), diffStore.lastRecordedVersion(pair))
    diffStore.removeLatestRecordedVersion(pair)
    assertEquals(None, diffStore.lastRecordedVersion(pair))
  }

  @Theory
  def shouldTileEvents(scenario:TileScenario) = {
    scenario.events.foreach(e => diffStore.addReportableUnmatchedEvent(e.id, e.timestamp, "", "", e.timestamp))
    scenario.zoomLevels.foreach{ case (zoom, expected) => {
      expected.foreach{ case (pair, tileGroups) => {
        tileGroups.foreach(group => {
          val retrieved = diffStore.retrieveEventTiles(DiffaPairRef(pair, scenario.domain), zoom, group.lowerBound)
          assertEquals("Failure @ zoom level %s; ".format(zoom), group.tiles, retrieved.get.tiles)
        })
      }}
    }}
  }

  @Test
  def unmatchedEventsShouldAggregate = {

    diffStore.clearAllDifferences
    val pair = DiffaPairRef("pair1", "domain")
    val start = new DateTime(2009,6,6,14,15,0,0,DateTimeZone.UTC)
    val end = start.plusDays(1)
    val interval = new Interval(start, end)

    val eventTime = start.plusMinutes(1)

    diffStore.addReportableUnmatchedEvent(VersionID(pair, "aaz"), eventTime, "", "", eventTime)

    val expected = Map(
      ZoomCache.QUARTER_HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.QUARTER_HOURLY), 1)
      ),
      ZoomCache.HALF_HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.HALF_HOURLY), 1)
      ),
      ZoomCache.HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.HOURLY), 1)
      ),
      ZoomCache.TWO_HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.TWO_HOURLY), 1)
      ),
      ZoomCache.FOUR_HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.FOUR_HOURLY), 1)
      ),
      ZoomCache.EIGHT_HOURLY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.EIGHT_HOURLY), 1)
      ),
      ZoomCache.DAILY -> Seq(
        AggregateEvents(ZoomCache.containingInterval(eventTime, ZoomCache.DAILY), 1)
      )
    )

    expected.foreach{ case (zoomLevel, events) => {
      val aggregates = diffStore.aggregateUnmatchedEvents(pair, interval, zoomLevel)
      assertEquals(events, aggregates)
    }}
  }

  @Test
  def tilesShouldBeWithinTimeSpan = ZoomCache.levels.foreach(tilesShouldBeWithinTimeSpanAtZoomLevel(_))

  private def tilesShouldBeWithinTimeSpanAtZoomLevel(zoomLevel:Int) {

    val observationTime = new DateTime(2008,9,7,0,0,0,0)
    val backOneWeek = observationTime.minusWeeks(1)
    val tileGroupInterval = ZoomCache.containingTileGroupInterval(backOneWeek.minusMinutes(1), zoomLevel)
    val queryTime = tileGroupInterval.getStart

    val pair = DiffaPairRef("pair1", "domain")

    diffStore.clearAllDifferences

    diffStore.addReportableUnmatchedEvent(VersionID(pair, "10a"), tileGroupInterval.getEnd.plusMinutes(1), "", "", observationTime)
    diffStore.addReportableUnmatchedEvent(VersionID(pair, "10b"), tileGroupInterval.getEnd.minusMinutes(1), "", "", observationTime)
    diffStore.addReportableUnmatchedEvent(VersionID(pair, "10c"), tileGroupInterval.getStart.plusMinutes(1), "", "", observationTime)
    diffStore.addReportableUnmatchedEvent(VersionID(pair, "10d"), tileGroupInterval.getStart.minusMinutes(1), "", "", observationTime)

    // Deliberately allow passing a potentially unaligned timestamp
    val tiles = diffStore.retrieveEventTiles(pair, zoomLevel, queryTime)

    val firstIntervalStart = ZoomCache.containingInterval(tileGroupInterval.getEnd.minusMinutes(1), zoomLevel).getStart
    val secondIntervalStart = ZoomCache.containingInterval(tileGroupInterval.getStart.plusMinutes(1), zoomLevel).getStart

    // Special case for daily granularity - a daily zoom level will result in only one tile group,
    // hence the aggregate will contain both events
    val expectedAggregates = if (firstIntervalStart == secondIntervalStart) {
      Map(firstIntervalStart -> 2)
    }
    else {
      Map(firstIntervalStart -> 1, secondIntervalStart -> 1)
    }

    assertEquals("Zoom level %s;".format(zoomLevel), TileGroup(queryTime, expectedAggregates), tiles.get)
  }

  @Test
  def dirtyTilesShouldNotAffectOtherTimeRanges = ZoomCache.levels.foreach(outOfRangeTilesShouldNotBeMarkerDirty(_))

  private def outOfRangeTilesShouldNotBeMarkerDirty(zoomLevel:Int) {
    val observationTime = new DateTime(1977,4,4,0,0,0,0)
    val firstEventTime = observationTime.minusMinutes(1)
    val secondEventTime = observationTime.minusMinutes(1).minusDays(1)

    val alignedTileStart = ZoomCache.containingInterval(firstEventTime, zoomLevel).getStart
    val alignedTileGroupStart = ZoomCache.containingTileGroupInterval(firstEventTime, zoomLevel).getStart

    val pair = DiffaPairRef("pair1", "domain")

    // Add an event to the store and verify that the correct tile cache is built for that event
    diffStore.addReportableUnmatchedEvent(VersionID(pair, "17a"), firstEventTime, "", "", observationTime)

    def verifyTilesOfFirstGroup() = {
      val tiles = diffStore.retrieveEventTiles(pair, zoomLevel, firstEventTime)
      assertEquals("Zoom level %s;".format(zoomLevel), TileGroup(alignedTileGroupStart, Map(alignedTileStart -> 1) ), tiles.get)
    }

    verifyTilesOfFirstGroup()

    // Add a second event to the store for a different time frame and verify that the cache of the first event is not affected
    diffStore.addReportableUnmatchedEvent(VersionID(pair, "17b"), secondEventTime, "", "", observationTime)

    verifyTilesOfFirstGroup()

  }

  @Test
  def eventsShouldUpdateZoomCache = ZoomCache.levels.foreach(playThroughEventsAtZoomLevel(_))

  private def playThroughEventsAtZoomLevel(zoomLevel:Int) = {

    val observationTime = new DateTime()
    val timestamp1 = observationTime.minusMinutes(ZoomCache.zoom(zoomLevel) + 1)
    val timestamp2 = observationTime.minusMinutes(ZoomCache.zoom(zoomLevel) + 2)

    val pair = DiffaPairRef("pair1", "domain")

    val id1 = VersionID(pair, "7a")
    val id2 = VersionID(pair, "7b")

    diffStore.clearAllDifferences

    diffStore.addReportableUnmatchedEvent(id1, timestamp1, "", "", observationTime)
    validateZoomRange(timestamp1, pair, zoomLevel, timestamp1)

    diffStore.addReportableUnmatchedEvent(id2, timestamp2, "", "", observationTime)
    validateZoomRange(timestamp2, pair, zoomLevel, timestamp1, timestamp2)

    diffStore.addMatchedEvent(id2, "")
    validateZoomRange(timestamp2, pair, zoomLevel, timestamp1)

    diffStore.addMatchedEvent(id1, "")
    val tileGroup = diffStore.retrieveEventTiles(pair, zoomLevel, timestamp1)
    assertTrue(tileGroup.get.tiles.isEmpty)
  }

  private def validateZoomRange(timestamp:DateTime, pair:DiffaPairRef, zoomLevel:Int, eventTimes:DateTime*) = {

    val expectedTiles = new scala.collection.mutable.HashMap[DateTime,Int]
    eventTimes.foreach(time => {

      val interval = ZoomCache.containingInterval(time, zoomLevel)
      val startTime = interval.getStart

      expectedTiles.get(startTime) match {
        case None    => expectedTiles(startTime) = 1
        case Some(x) => expectedTiles(startTime) = x + 1
      }
    })

    // Deliberately allow passing a potentially unaligned timestamp

    val tileGroup = diffStore.retrieveEventTiles(pair, zoomLevel, timestamp)

    assertEquals("Expected tile set not in range at zoom level %s;".format(zoomLevel), expectedTiles, tileGroup.get.tiles)
  }

  //
  // Helpers
  //

  def validateUnmatchedEvent(event:DifferenceEvent, id:VersionID, usVsn:String, dsVsn:String, timestamp:DateTime, seen:DateTime) {
    assertEquals(id, event.objId)
    assertEquals(MatchState.UNMATCHED, event.state)
    assertEquals(usVsn, event.upstreamVsn)
    assertEquals(dsVsn, event.downstreamVsn)
    assertEquals(timestamp, event.detectedAt)
    assertEquals(seen, event.lastSeen)
  }

  def validateMatchedEvent(event:DifferenceEvent, id:VersionID, vsn:String, now:DateTime) {
    assertEquals(id, event.objId)
    assertEquals(MatchState.MATCHED, event.state)
    assertEquals(vsn, event.upstreamVsn)
    assertEquals(vsn, event.downstreamVsn)
    assertTrue(!event.detectedAt.isBefore(now))      // Detection should be some time at or after now
    assertTrue(!event.lastSeen.isBefore(now))        // Last seen should be some time at or after now
  }
}

object HibernateDomainDifferenceStoreTest {

  @DataPoint def tiles = TileScenario("domain", new Interval(new DateTime(2002,10,4,14,2,0,0, DateTimeZone.UTC),
                                                             new DateTime(2002,10,5,14,5,30,0, DateTimeZone.UTC)),
      Seq(
        // - 1 day
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1a"), timestamp = new DateTime(2002,10,4,14,2,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1b"), timestamp = new DateTime(2002,10,4,14,3,0,0, DateTimeZone.UTC)),
        // - 8 hours
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1c"), timestamp = new DateTime(2002,10,5,6,7,0,0, DateTimeZone.UTC)),
        // - 4 hours
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1d"), timestamp = new DateTime(2002,10,5,10,9,0,0, DateTimeZone.UTC)),
        // - 2 hours
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1e"), timestamp = new DateTime(2002,10,5,12,2,0,0, DateTimeZone.UTC)),
        // - 1 hour
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1f"), timestamp = new DateTime(2002,10,5,13,11,0,0, DateTimeZone.UTC)),
        // - 45 minutes
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1g"), timestamp = new DateTime(2002,10,5,13,21,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1h"), timestamp = new DateTime(2002,10,5,13,22,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1i"), timestamp = new DateTime(2002,10,5,13,23,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1j"), timestamp = new DateTime(2002,10,5,13,24,0,0, DateTimeZone.UTC)),
        // - 30 minutes
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1k"), timestamp = new DateTime(2002,10,5,13,32,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1l"), timestamp = new DateTime(2002,10,5,13,33,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1m"), timestamp = new DateTime(2002,10,5,13,34,0,0, DateTimeZone.UTC)),
        // - 15 minutes
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1n"), timestamp = new DateTime(2002,10,5,13,47,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1p"), timestamp = new DateTime(2002,10,5,13,48,0,0, DateTimeZone.UTC)),
        // no offset
        ReportableEvent(id = VersionID(DiffaPairRef("pair1", "domain"), "1q"), timestamp = new DateTime(2002,10,5,14,4,0,0, DateTimeZone.UTC)),
        // 2nd pair
        ReportableEvent(id = VersionID(DiffaPairRef("pair2", "domain"), "2a"), timestamp = new DateTime(2002,10,5,14,5,0,0, DateTimeZone.UTC)),
        ReportableEvent(id = VersionID(DiffaPairRef("pair2", "domain"), "2b"), timestamp = new DateTime(2002,10,5,14,5,30,0, DateTimeZone.UTC))
      ),
      Map(QUARTER_HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC)  -> 1,
                                        new DateTime(2002,10,5,13,45,0,0, DateTimeZone.UTC) -> 2,
                                        new DateTime(2002,10,5,13,30,0,0, DateTimeZone.UTC) -> 3,
                                        new DateTime(2002,10,5,13,15,0,0, DateTimeZone.UTC) -> 4,
                                        new DateTime(2002,10,5,13,0,0,0, DateTimeZone.UTC)  -> 1,
                                        new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC)  -> 1,
                                        new DateTime(2002,10,5,10,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,6,0,0,0, DateTimeZone.UTC)   -> 1)),
                          TileGroup(new DateTime(2002,10,4,8,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,14,0,0,0, DateTimeZone.UTC)  -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC)  -> 2)))
         ),
          HALF_HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC)  -> 1,
                                        new DateTime(2002,10,5,13,30,0,0, DateTimeZone.UTC) -> 5,
                                        new DateTime(2002,10,5,13,0,0,0, DateTimeZone.UTC)  -> 5,
                                        new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,10,0,0,0, DateTimeZone.UTC)  -> 1,
                                        new DateTime(2002,10,5,6,0,0,0, DateTimeZone.UTC)   -> 1)),
                          TileGroup(new DateTime(2002,10,4,12,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,14,0,0,0, DateTimeZone.UTC)  -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC)  -> 2)))
         ),
          HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC) -> 1,
                                         new DateTime(2002,10,5,13,0,0,0, DateTimeZone.UTC) -> 10,
                                         new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC) -> 1,
                                         new DateTime(2002,10,5,10,0,0,0, DateTimeZone.UTC) -> 1,
                                         new DateTime(2002,10,5,6,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,14,0,0,0, DateTimeZone.UTC) -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC) -> 2)))
         ),
          TWO_HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC) -> 1,
                                          new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC) -> 11,
                                          new DateTime(2002,10,5,10,0,0,0, DateTimeZone.UTC) -> 1,
                                          new DateTime(2002,10,5,6,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,14,0,0,0, DateTimeZone.UTC) -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,14,0,0,0, DateTimeZone.UTC) -> 2)))
         ),
          FOUR_HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC) -> 12,
                                          new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC)  -> 1,
                                          new DateTime(2002,10,5,4,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,12,0,0,0, DateTimeZone.UTC) -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,12,0,0,0, DateTimeZone.UTC) -> 2)))
         ),
          EIGHT_HOURLY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC)  -> 13,
                                          new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC)  -> 1)),
                          TileGroup(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,8,0,0,0, DateTimeZone.UTC)  -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,8,0,0,0, DateTimeZone.UTC)  -> 2)))
         ),
          DAILY -> Map(
           "pair1" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC) -> 14)),
                          TileGroup(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,4,0,0,0,0, DateTimeZone.UTC) -> 2))),
           "pair2" -> Seq(TileGroup(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC),
                                    Map(new DateTime(2002,10,5,0,0,0,0, DateTimeZone.UTC) -> 2)))
         )
      )
  )

  case class ReportableEvent(
    id:VersionID,
    timestamp:DateTime
  )

  case class TileScenario(
    domain:String,
    timespan:Interval,
    events:Seq[ReportableEvent],
    zoomLevels:Map[Int,Map[String,Seq[TileGroup]]]
  )

  private val config =
      new Configuration().
        addResource("net/lshift/diffa/kernel/config/Config.hbm.xml").
        addResource("net/lshift/diffa/kernel/differencing/DifferenceEvents.hbm.xml").
        setProperty("hibernate.dialect", DatabaseEnvironment.DIALECT).
        setProperty("hibernate.connection.url", DatabaseEnvironment.substitutableURL("target/domainCache")).
        setProperty("hibernate.connection.driver_class", DatabaseEnvironment.DRIVER).
        setProperty("hibernate.connection.username", DatabaseEnvironment.USERNAME).
        setProperty("hibernate.connection.password", DatabaseEnvironment.PASSWORD).
        setProperty("hibernate.cache.region.factory_class", "net.sf.ehcache.hibernate.EhCacheRegionFactory").
        setProperty("hibernate.connection.autocommit", "true") // Turn this on to make the tests repeatable,
                                                               // otherwise the preparation step will not get committed

  val cacheManager = new CacheManager()

  val sf:SessionFactory = config.buildSessionFactory
  (new HibernateConfigStorePreparationStep).prepare(sf, config)
  val dialect = Class.forName(DatabaseEnvironment.DIALECT).newInstance().asInstanceOf[Dialect]
  val diffStore = new HibernateDomainDifferenceStore(sf, cacheManager, dialect)

}