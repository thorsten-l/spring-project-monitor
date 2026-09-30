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
package l9g.webapp.springprojectmonitor.scan;

import java.time.Duration;
import java.time.LocalDateTime;
import l9g.webapp.springprojectmonitor.config.DockerHost;

/**
 * Zustand des letzten bzw. laufenden Scans für die Oberfläche.
 *
 * @param waitingForKey Host, für den ssh gerade auf die Bestätigung am Security-Key wartet
 */
public record ScanStatus(boolean running, LocalDateTime finished, Duration duration, String error,
  DockerHost waitingForKey)
{
  public static final ScanStatus NEVER = new ScanStatus(false, null, null, null, null);

  public ScanStatus start()
  {
    return new ScanStatus(true, finished, duration, error, null);
  }

  public ScanStatus waitingFor(DockerHost host)
  {
    return new ScanStatus(running, finished, duration, error, host);
  }

  public long getSeconds()
  {
    return duration == null ? 0 : duration.toSeconds();
  }

}
