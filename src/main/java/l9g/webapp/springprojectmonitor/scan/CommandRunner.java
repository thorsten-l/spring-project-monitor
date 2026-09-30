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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Führt Kommandozeilenprogramme (git, svn, hg) ohne interaktive Abfragen aus.
 */
public final class CommandRunner
{
  public record Result(int exitCode, String out, String err)
  {
    public boolean ok()
    {
      return exitCode == 0;
    }

    /**
     * Letzte Zeile von stderr, für Fehlermeldungen.
     */
    public String lastErrorLine()
    {
      String[] lines = err.strip().split("\n");
      return lines[lines.length - 1].isBlank() ? "rc=" + exitCode : lines[lines.length - 1];
    }
  }

  // blockierendes Lesen der Prozess-Streams nicht im Common-Pool
  private static final Executor READERS = Executors.newVirtualThreadPerTaskExecutor();

  private CommandRunner()
  {
  }

  public static Result run(Path directory, Duration timeout, String... command)
  {
    ProcessBuilder builder = new ProcessBuilder(List.of(command)).directory(directory.toFile());
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=10");
    builder.environment().put("HGPLAIN", "1");
    builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

    try
    {
      Process process = builder.start();
      // beide Streams parallel lesen, damit der Timeout auch bei hängenden Prozessen greift
      CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()), READERS);
      CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()), READERS);
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
      {
        process.destroyForcibly();
        return new Result(-1, "", "timeout");
      }
      return new Result(process.exitValue(), out.join().strip(), err.join().strip());
    }
    catch (IOException e)
    {
      return new Result(-1, "", e.getMessage());
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      return new Result(-1, "", "interrupted");
    }
  }

  private static String read(InputStream in)
  {
    try (in)
    {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    catch (IOException e)
    {
      return "";
    }
  }

}
