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

package net.lshift.diffa.kernel.scheduler

import net.lshift.diffa.kernel.config.PairRef

/**
 * Trait to be implemented by Scan Scheduler implementations.
 */
trait ScanScheduler {
  /**
   * Handler for new pair creation or update of an existing one. This method will ensure that the scheduler
   * takes account of the given pair's configuration.
   */
  def onUpdatePair(pair:PairRef)

  /**
   * Handler for pair deletion.
   */
  def onDeletePair(pair:PairRef)
}