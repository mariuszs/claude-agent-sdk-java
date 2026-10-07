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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.claude.agent.sdk.exceptions.ResultException;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * How {@link ClaudeAsyncClient} reports a CLI whose output ends, as
 * {@link CliExitStatusTest} pins it for {@link ClaudeSyncClient}.
 *
 * <p>
 * A non-zero exit before the result fails the turn's Flux with a
 * {@link TransportException} carrying the exit status and the CLI's last stderr lines. A
 * turn ends at its result, so a non-zero exit after the result fails
 * {@link ClaudeAsyncClient#receiveMessages()} and the next turn, with a
 * {@link ResultException} when that result reported an error. A zero exit completes the
 * Flux. Once the output has ended, every later read ends at once, the same way.
 * </p>
 *
 * <p>
 * The CLI is a generated shell stub. Nothing here starts a real Claude CLI, needs
 * credentials, or bills model usage. The stubs answer as soon as they read the prompt.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("ClaudeAsyncClient reports a CLI whose output ends")
class AsyncCliExitStatusTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INIT = """
			{"type":"system","subtype":"init","session_id":"stub-session","model":"stub","permissionMode":"default"}""";

	private static final String RESULT = """
			{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,\
			"session_id":"stub-session","total_cost_usd":0.0,"result":"done"}""";

	private static final String ERROR_RESULT = """
			{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":1,"duration_api_ms":1,\
			"num_turns":1,"session_id":"stub-session","total_cost_usd":0.0,\
			"errors":["Reached maximum number of turns (60)"]}""";

	@TempDir
	Path tempDir;

	private ClaudeAsyncClient client;

	@AfterEach
	void closeClient() {
		if (client != null) {
			client.close().block(TIMEOUT);
		}
	}

	@Test
	@DisplayName("a non-zero exit before the result fails the turn with the exit status and stderr")
	void nonZeroExitBeforeResultFailsTheTurn() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo 'Error: something broke' >&2
				exit 3
				""".formatted(INIT)));
		List<Message> received = new ArrayList<>();

		TransportException error = catchThrowableOfType(TransportException.class,
				() -> collect(client.connect("hello").messages(), received));

		assertThat(received).singleElement().isInstanceOf(SystemMessage.class);
		assertThat(error).isNotNull().isNotInstanceOf(ResultException.class);
		assertThat(error.getExitCode()).isEqualTo(3);
		assertThat(error.getStderr()).isEqualTo("Error: something broke");
		assertThat(error).hasMessageStartingWith("Claude CLI exited before its result")
			.hasMessageContaining("exit code: 3");
	}

	@Test
	@DisplayName("a CLI that ends before the turn is subscribed still fails the turn")
	void exitBeforeTheTurnIsSubscribedFailsTheTurn() throws Exception {
		client = newClient(stubCli("""
				exit 3
				"""));
		client.connect().block(TIMEOUT);
		awaitOutputEnded();

		assertThatThrownBy(() -> collect(client.receiveResponse(), new ArrayList<>()))
			.isInstanceOfSatisfying(TransportException.class, e -> assertThat(e.getExitCode()).isEqualTo(3));
	}

	@Test
	@DisplayName("a CLI that exits long after its output ended still reports its status and stderr")
	void slowExitAfterOutputEndedFailsTheTurn() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				exec >&-
				sleep 7
				echo 'Error: gave up' >&2
				exit 3
				"""));

		TransportException error = catchThrowableOfType(TransportException.class,
				() -> collect(client.connect("hello").messages(), new ArrayList<>()));

		assertThat(error).isNotNull();
		assertThat(error.getExitCode()).isEqualTo(3);
		assertThat(error.getStderr()).isEqualTo("Error: gave up");
	}

	@Test
	@DisplayName("close() ends the wait for a CLI that does not exit after its output ended")
	void closeEndsTheWaitForAHangingCli() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				exec >&-
				sleep 60
				"""));
		CompletableFuture<List<Message>> receiving = CompletableFuture.supplyAsync(() -> {
			List<Message> received = new ArrayList<>();
			collect(client.connect("hello").messages(), received);
			return received;
		});

		// Past the grace period in which the stream would end on its own.
		Thread.sleep(6_000);
		assertThat(receiving).isNotDone();

		long start = System.nanoTime();
		client.close().block(TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
		assertThat(receiving.get(3, TimeUnit.SECONDS)).isEmpty();
	}

	@Test
	@DisplayName("a CLI killed by a signal reports 128 plus the signal number")
	void killedBySignalReportsTheSignalStatus() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				kill -9 $$
				"""));

		TransportException error = catchThrowableOfType(TransportException.class,
				() -> collect(client.connect("hello").messages(), new ArrayList<>()));

		assertThat(error.getExitCode()).isEqualTo(137);
		assertThat(error.getStderr()).isNull();
	}

	@Test
	@DisplayName("a non-zero exit before a later turn's result fails that turn")
	void nonZeroExitInALaterTurnFailsThatTurn() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				read -r prompt
				exit 3
				""".formatted(INIT, RESULT)));
		List<Message> first = new ArrayList<>();
		collect(client.connect("first").messages(), first);
		assertThat(first).last().isInstanceOf(ResultMessage.class);

		TransportException error = catchThrowableOfType(TransportException.class,
				() -> collect(client.query("second").messages(), new ArrayList<>()));

		assertThat(error).isNotNull();
		assertThat(error.getExitCode()).isEqualTo(3);
	}

	@Test
	@DisplayName("a zero exit before the result completes the turn without an error")
	void zeroExitBeforeResultCompletesQuietly() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				exit 0
				""".formatted(INIT)));
		List<Message> received = new ArrayList<>();

		collect(client.connect("hello").messages(), received);

		assertThat(received).singleElement().isInstanceOf(SystemMessage.class);
	}

	@Test
	@DisplayName("a turn ends at its result, and the non-zero exit after it fails receiveMessages()")
	void nonZeroExitAfterResultFailsReceiveMessages() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				echo 'Error: crashed after the turn' >&2
				exit 3
				""".formatted(INIT, RESULT)));
		client.connect().block(TIMEOUT);
		client.query("hello").messages().subscribe();
		List<ParsedMessage> received = new ArrayList<>();

		TransportException error = catchThrowableOfType(TransportException.class,
				() -> client.receiveMessages().doOnNext(received::add).blockLast(TIMEOUT));

		assertThat(received).last().satisfies(m -> assertThat(m.asMessage()).isInstanceOf(ResultMessage.class));
		assertThat(error).isNotNull().isNotInstanceOf(ResultException.class);
		assertThat(error.getExitCode()).isEqualTo(3);
		assertThat(error.getStderr()).isEqualTo("Error: crashed after the turn");
		assertThat(error).hasMessageStartingWith("Claude CLI exited after its result");
	}

	@Test
	@DisplayName("the exit after a turn's result fails the next turn")
	void exitAfterResultFailsTheNextTurn() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				exit 3
				""".formatted(INIT, RESULT)));
		List<Message> first = new ArrayList<>();
		collect(client.connect("first").messages(), first);
		assertThat(first).last().isInstanceOf(ResultMessage.class);

		// The CLI may or may not have ended by the time query() sends: either the send
		// fails, or the turn does. Either way the status is reported.
		TransportException error = catchThrowableOfType(TransportException.class,
				() -> collect(client.query("second").messages(), new ArrayList<>()));

		assertThat(error).isNotNull();
		assertThat(error.getExitCode()).isEqualTo(3);
	}

	@Test
	@DisplayName("query() after the CLI's output ended fails with the exit status and stderr")
	void queryAfterTheCliEndedFails() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo 'Error: crashed after the turn' >&2
				exit 3
				"""));
		client.connect("first").messages().onErrorComplete().blockLast(TIMEOUT);

		assertThatThrownBy(() -> collect(client.query("second").messages(), new ArrayList<>()))
			.isInstanceOfSatisfying(TransportException.class, e -> {
				assertThat(e.getExitCode()).isEqualTo(3);
				assertThat(e.getStderr()).isEqualTo("Error: crashed after the turn");
				assertThat(e).hasMessageStartingWith("Cannot send to the Claude CLI: its output has ended");
			});
	}

	@Test
	@DisplayName("an error result and the exit after it fail with a ResultException carrying the result")
	void errorResultThenExitFailsWithResultException() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				exit 1
				""".formatted(INIT, ERROR_RESULT)));
		client.connect().block(TIMEOUT);
		client.query("hello").messages().subscribe();

		ResultException error = catchThrowableOfType(ResultException.class,
				() -> client.receiveMessages().blockLast(TIMEOUT));

		assertThat(error).isNotNull();
		assertThat(error.getExitCode()).isEqualTo(1);
		assertThat(error.getResult().subtype()).isEqualTo("error_max_turns");
		assertThat(error).hasMessageStartingWith(
				"Claude CLI returned an error result: Reached maximum number of turns (60)");
	}

	@Test
	@DisplayName("a zero exit after the result completes receiveMessages()")
	void zeroExitAfterResultCompletesReceiveMessages() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				exit 0
				""".formatted(INIT, RESULT)));
		client.connect().block(TIMEOUT);
		client.query("hello").messages().subscribe();
		List<ParsedMessage> received = new ArrayList<>();

		client.receiveMessages().doOnNext(received::add).blockLast(TIMEOUT);

		assertThat(received).last().satisfies(m -> assertThat(m.asMessage()).isInstanceOf(ResultMessage.class));
	}

	@Test
	@DisplayName("a read after the stream ended completes at once instead of waiting")
	void readAfterTheEndCompletesAtOnce() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				exit 0
				"""));
		collect(client.connect("hello").messages(), new ArrayList<>());

		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertThat(client.receiveResponse().collectList().block()).isEmpty();
			client.receiveMessages().blockLast();
		});
	}

	@Test
	@DisplayName("a read after a failed stream fails with the same error again")
	void readAfterAFailedEndFailsAgain() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				exit 3
				"""));
		TransportException first = catchThrowableOfType(TransportException.class,
				() -> collect(client.connect("hello").messages(), new ArrayList<>()));
		assertThat(first).isNotNull();

		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertThatThrownBy(() -> client.receiveResponse().blockLast()).isSameAs(first);
			assertThatThrownBy(() -> client.receiveMessages().blockLast()).isSameAs(first);
		});
	}

	@Test
	@DisplayName("a normal turn ends at its result and the client closes cleanly")
	void normalTurnEndsAtItsResult() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				while read -r line; do :; done
				""".formatted(INIT, RESULT)));
		List<Message> received = new ArrayList<>();

		collect(client.connect("hello").messages(), received);

		assertThat(received).hasSize(2).last().isInstanceOf(ResultMessage.class);
	}

	// ---------------------------------------------------------------- helpers

	private ClaudeAsyncClient newClient(String cli) {
		return ClaudeClient.async().workingDirectory(tempDir).claudePath(cli).timeout(TIMEOUT).build();
	}

	private static void collect(Flux<Message> messages, List<Message> into) {
		messages.doOnNext(into::add).blockLast(TIMEOUT);
	}

	/** Waits until the client no longer sees a running CLI. */
	private void awaitOutputEnded() throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (client.isConnected() && System.nanoTime() < deadline) {
			Thread.sleep(25);
		}
		// The exit status is recorded just after the process ends.
		Thread.sleep(500);
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
