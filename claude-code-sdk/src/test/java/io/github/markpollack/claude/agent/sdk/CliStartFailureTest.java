/*
 * Copyright 2025 Mark Pollack
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.markpollack.claude.agent.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import io.github.markpollack.claude.agent.sdk.exceptions.CLINotFoundException;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code connect()} reports when the CLI process cannot be started: the exception
 * names the program and the reason, and carries no exit status because the process never
 * ran. Nothing here starts a real Claude CLI.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("connect() names a CLI that cannot be started")
class CliStartFailureTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("sync: a missing CLI path fails with CLINotFoundException naming the path")
	void syncMissingCli() {
		String missing = tempDir.resolve("no-such-claude").toString();

		try (ClaudeSyncClient client = syncClient(missing)) {
			assertThatThrownBy(() -> client.connect("hello")).isInstanceOfSatisfying(CLINotFoundException.class,
					e -> assertNeverRan(e, "Claude CLI not found: " + missing));
		}
	}

	@Test
	@DisplayName("sync: a CLI file that is not executable fails with CLINotFoundException naming the path")
	void syncNonExecutableCli() throws IOException {
		Path cli = tempDir.resolve("claude");
		Files.writeString(cli, "#!/bin/sh\n", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rw-r--r--"));

		try (ClaudeSyncClient client = syncClient(cli.toString())) {
			assertThatThrownBy(() -> client.connect("hello")).isInstanceOfSatisfying(CLINotFoundException.class,
					e -> assertNeverRan(e, "Claude CLI is not executable: " + cli));
		}
	}

	@Test
	@DisplayName("sync: a command name that is not on PATH fails with CLINotFoundException naming it")
	void syncCommandNotOnPath() {
		String command = "claude-sdk-test-no-such-command";

		try (ClaudeSyncClient client = syncClient(command)) {
			assertThatThrownBy(() -> client.connect()).isInstanceOfSatisfying(CLINotFoundException.class,
					e -> assertNeverRan(e, "Claude CLI not found on PATH: " + command));
		}
	}

	@Test
	@DisplayName("sync: a missing working directory is named, not reported as a missing CLI")
	void syncMissingWorkingDirectory() throws IOException {
		Path cli = tempDir.resolve("claude");
		Files.writeString(cli, "#!/bin/sh\n", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
		Path missingDir = tempDir.resolve("no-such-dir");

		try (ClaudeSyncClient client = ClaudeClient.sync()
			.workingDirectory(missingDir)
			.claudePath(cli.toString())
			.timeout(TIMEOUT)
			.build()) {
			assertThatThrownBy(() -> client.connect()).isExactlyInstanceOf(TransportException.class)
				.satisfies(e -> assertNeverRan((TransportException) e, "Cannot start the Claude CLI " + cli
						+ ": the working directory does not exist: " + missingDir));
		}
	}

	@Test
	@DisplayName("async: a missing CLI path fails with CLINotFoundException naming the path")
	void asyncMissingCli() {
		String missing = tempDir.resolve("no-such-claude").toString();
		ClaudeAsyncClient client = ClaudeClient.async()
			.workingDirectory(tempDir)
			.claudePath(missing)
			.timeout(TIMEOUT)
			.build();
		try {
			assertThatThrownBy(() -> client.connect().block(TIMEOUT)).isInstanceOfSatisfying(CLINotFoundException.class,
					e -> assertNeverRan(e, "Claude CLI not found: " + missing));
		}
		finally {
			client.close().block(TIMEOUT);
		}
	}

	private ClaudeSyncClient syncClient(String cli) {
		return ClaudeClient.sync().workingDirectory(tempDir).claudePath(cli).timeout(TIMEOUT).build();
	}

	private static void assertNeverRan(TransportException e, String message) {
		assertThat(e).hasMessage(message).hasCauseInstanceOf(IOException.class);
		assertThat(e.getExitCode()).isNull();
		assertThat(e.getStderr()).isNull();
	}

}
