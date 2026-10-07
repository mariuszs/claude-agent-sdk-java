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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.streaming.MessageReceiver;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * How {@link ClaudeSyncClient} reports a CLI that exits before the current turn's
 * {@link ResultMessage}.
 *
 * <p>
 * A non-zero exit before the result fails the iteration with a {@link TransportException}
 * carrying the exit status and the CLI's last stderr lines, so a caller can tell a
 * crashed or killed CLI from one that simply closed its output. A zero exit, and any exit
 * after the result, end the iteration as they always have.
 * </p>
 *
 * <p>
 * The CLI is a generated shell stub. Nothing here starts a real Claude CLI, needs
 * credentials, or bills model usage.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("ClaudeSyncClient reports a CLI that exits before its result")
class CliExitStatusTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INIT = """
			{"type":"system","subtype":"init","session_id":"stub-session","model":"stub","permissionMode":"default"}""";

	private static final String RESULT = """
			{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,\
			"session_id":"stub-session","total_cost_usd":0.0,"result":"done"}""";

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("a non-zero exit before the result throws with the exit status and stderr")
	void nonZeroExitBeforeResultThrows() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				echo 'Error: something broke' >&2
				exit 3
				""".formatted(INIT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");
			Iterator<ParsedMessage> response = client.receiveResponse();
			List<Message> received = new ArrayList<>();

			TransportException error = catchThrowableOfType(TransportException.class, () -> drain(response, received));

			assertThat(received).singleElement().isInstanceOf(SystemMessage.class);
			assertThat(error.getExitCode()).isEqualTo(3);
			assertThat(error.getStderr()).isEqualTo("Error: something broke");
			assertThat(error).hasMessageContaining("exit code: 3").hasMessageContaining("Error: something broke");
		}
	}

	@Test
	@DisplayName("a CLI that exits long after its output ended still reports its status and stderr")
	void slowExitAfterOutputEndedThrows() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				exec >&-
				sleep 7
				echo 'Error: gave up' >&2
				exit 3
				""".formatted(INIT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");
			List<Message> received = new ArrayList<>();

			TransportException error = catchThrowableOfType(TransportException.class,
					() -> drain(client.receiveResponse(), received));

			assertThat(received).singleElement().isInstanceOf(SystemMessage.class);
			assertThat(error).isNotNull();
			assertThat(error.getExitCode()).isEqualTo(3);
			assertThat(error.getStderr()).isEqualTo("Error: gave up");
		}
	}

	@Test
	@DisplayName("close() ends the wait for a CLI that does not exit after its output ended")
	void closeEndsTheWaitForAHangingCli() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				exec >&-
				sleep 60
				""".formatted(INIT));

		ClaudeSyncClient client = newClient(cli);
		client.connect("hello");
		CompletableFuture<List<Message>> receiving = CompletableFuture.supplyAsync(() -> {
			List<Message> received = new ArrayList<>();
			drain(client.receiveResponse(), received);
			return received;
		});

		// Past the grace period in which the stream used to end on its own.
		Thread.sleep(6_000);
		assertThat(receiving).isNotDone();

		long start = System.nanoTime();
		client.close();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
		assertThat(receiving.get(3, TimeUnit.SECONDS)).singleElement().isInstanceOf(SystemMessage.class);
	}

	@Test
	@DisplayName("a CLI killed by a signal reports 128 plus the signal number")
	void killedBySignalReportsTheSignalStatus() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				kill -9 $$
				""".formatted(INIT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");

			TransportException error = catchThrowableOfType(TransportException.class,
					() -> drain(client.receiveResponse(), new ArrayList<>()));

			assertThat(error.getExitCode()).isEqualTo(137);
			assertThat(error.getStderr()).isNull();
		}
	}

	@Test
	@DisplayName("the message receivers report the exit status too")
	void messageReceiverThrowsOnNonZeroExit() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				exit 3
				""".formatted(INIT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");
			MessageReceiver receiver = client.responseReceiver();

			assertThat(receiver.next()).isNotNull();
			assertThatThrownBy(receiver::next).isInstanceOfSatisfying(TransportException.class,
					e -> assertThat(e.getExitCode()).isEqualTo(3));
		}
	}

	@Test
	@DisplayName("a non-zero exit before a later turn's result throws for that turn")
	void nonZeroExitInALaterTurnThrows() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				read -r prompt
				exit 3
				""".formatted(INIT, RESULT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("first");
			List<Message> first = new ArrayList<>();
			drain(client.receiveResponse(), first);
			assertThat(first).last().isInstanceOf(ResultMessage.class);

			client.query("second");

			TransportException error = catchThrowableOfType(TransportException.class,
					() -> drain(client.receiveResponse(), new ArrayList<>()));
			assertThat(error.getExitCode()).isEqualTo(3);
		}
	}

	@Test
	@DisplayName("a zero exit before the result ends the iteration without an error, as before")
	void zeroExitBeforeResultEndsQuietly() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				exit 0
				""".formatted(INIT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");
			List<Message> received = new ArrayList<>();

			drain(client.receiveResponse(), received);

			assertThat(received).singleElement().isInstanceOf(SystemMessage.class);
		}
	}

	@Test
	@DisplayName("a non-zero exit after the result ends the iteration without an error, as before")
	void nonZeroExitAfterResultEndsQuietly() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				exit 3
				""".formatted(INIT, RESULT));

		try (ClaudeSyncClient client = newClient(cli)) {
			client.connect("hello");
			List<Message> received = new ArrayList<>();

			drain(client.receiveMessages(), received);

			assertThat(received).last().isInstanceOf(ResultMessage.class);
		}
	}

	@Test
	@DisplayName("a normal turn ends at its result and the client closes cleanly")
	void normalTurnEndsAtItsResult() throws Exception {
		String cli = stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				while read -r line; do :; done
				""".formatted(INIT, RESULT));

		try (ClaudeSyncClient client = newClient(cli)) {
			List<Message> received = new ArrayList<>();
			client.connectAndReceive("hello").forEach(received::add);

			assertThat(received).hasSize(2).last().isInstanceOf(ResultMessage.class);
		}
	}

	@Test
	@DisplayName("a missing CLI binary fails connect() without an exit status")
	void missingCliFailsConnect() {
		String missing = tempDir.resolve("no-such-claude").toString();

		try (ClaudeSyncClient client = newClient(missing)) {
			assertThatThrownBy(() -> client.connect("hello")).isInstanceOfSatisfying(TransportException.class, e -> {
				assertThat(e.getExitCode()).isNull();
				assertThat(e.getStderr()).isNull();
				assertThat(e).hasMessage("Failed to connect client")
					.cause()
					.hasMessage("Failed to start bidirectional session")
					.cause()
					.isInstanceOf(IOException.class)
					.hasMessageContaining("no-such-claude");
			});
		}
	}

	// ---------------------------------------------------------------- helpers

	private ClaudeSyncClient newClient(String cli) {
		return ClaudeClient.sync().workingDirectory(tempDir).claudePath(cli).timeout(TIMEOUT).build();
	}

	private static void drain(Iterator<ParsedMessage> messages, List<Message> into) {
		while (messages.hasNext()) {
			ParsedMessage parsed = messages.next();
			if (parsed.isRegularMessage()) {
				into.add(parsed.asMessage());
			}
		}
	}

	/**
	 * Writes a stand-in for the Claude CLI that runs the given shell body. The body
	 * decides what the CLI prints, what it writes to stderr, and how it ends.
	 */
	private String stubCli(String body) throws IOException {
		Path stub = Files.createTempFile(tempDir, "claude-stub-", ".sh");
		Files.writeString(stub, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
		return stub.toAbsolutePath().toString();
	}

}
