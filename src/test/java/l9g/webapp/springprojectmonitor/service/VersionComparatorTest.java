/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package l9g.webapp.springprojectmonitor.service;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionComparatorTest
{

  @Test
  void sortsNumericallyPerSegment()
  {
    List<String> sorted = Stream.of("3.5.16", "2.7.18", "4.1.1", "3.5.6", "2.2.9.RELEASE", "4.0.10")
      .sorted(VersionComparator.INSTANCE)
      .toList();

    assertThat(sorted).containsExactly("2.2.9.RELEASE", "2.7.18", "3.5.6", "3.5.16", "4.0.10", "4.1.1");
  }

  @Test
  void releaseBeforeSnapshotOfSameNumber()
  {
    assertThat(VersionComparator.INSTANCE.compare("1.0.0", "1.0.0-SNAPSHOT")).isNegative();
    assertThat(VersionComparator.INSTANCE.compare("0.10.2-SNAPSHOT", "0.9.1-SNAPSHOT")).isPositive();
  }

}
