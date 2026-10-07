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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
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

/**
 * A turn of {@link ClaudeAsyncClient} gets every message the CLI sends in answer to its
 * prompt, however fast the CLI answers.
 *
 * <p>
 * The CLI is a generated shell stub that answers as soon as it reads the prompt. Nothing
 * here starts a real Claude CLI, needs credentials, or bills model usage.
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("ClaudeAsyncClient hands a turn every message of its answer")
class AsyncTurnMessagesTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INIT = """
			{"type":"system","subtype":"init","session_id":"stub-session","model":"stub","permissionMode":"default"}""";

	private static final String RESULT = """
			{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,\
			"session_id":"stub-session","total_cost_usd":0.0,"result":"done"}""";

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
	@DisplayName("connect(prompt) gets the messages of a CLI that answers at once")
	void connectGetsAnImmediateAnswer() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				while read -r line; do :; done
				""".formatted(INIT, RESULT)));

		List<Message> received = collect(client.connect("hello").messages());

		assertThat(received).hasSize(2);
		assertThat(received.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(received.get(1)).isInstanceOf(ResultMessage.class);
	}

	@Test
	@DisplayName("query(prompt) gets the messages of a CLI that answers at once, turn after turn")
	void queryGetsAnImmediateAnswerEveryTurn() throws Exception {
		client = newClient(stubCli("""
				while read -r prompt; do
				  echo '%s'
				  echo '%s'
				done
				""".formatted(INIT, RESULT)));
		client.connect().block(TIMEOUT);

		for (int turn = 0; turn < 1000; turn++) {
			List<Message> received = collect(client.query("turn " + turn).messages());

			assertThat(received).as("turn %d", turn).hasSize(2).last().isInstanceOf(ResultMessage.class);
		}
	}

	@Test
	@DisplayName("text() completes for a CLI that answers at once")
	void textCompletesForAnImmediateAnswer() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				echo '%s'
				while read -r line; do :; done
				""".formatted(INIT, RESULT)));

		assertThat(client.connect("hello").text().block(TIMEOUT)).isEmpty();
	}

	@Test
	@DisplayName("a connect(prompt) refused on a connected client leaves the turn in progress alone")
	void refusedConnectLeavesTheTurnInProgress() throws Exception {
		client = newClient(stubCli("""
				read -r prompt
				echo '%s'
				sleep 2
				echo '%s'
				while read -r line; do :; done
				""".formatted(INIT, RESULT)));
		List<Message> received = new CopyOnWriteArrayList<>();
		CompletableFuture<List<Message>> turn = client.connect("first").messages().doOnNext(received::add).collectList().toFuture();
		awaitSize(received, 1);

		assertThatThrownBy(() -> client.connect("second").messages().blockLast(TIMEOUT))
			.isInstanceOf(TransportException.class)
			.hasMessageContaining("already connected");

		assertThat(turn.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).hasSize(2)
			.last()
			.isInstanceOf(ResultMessage.class);
	}

	// ---------------------------------------------------------------- helpers

	private ClaudeAsyncClient newClient(String cli) {
		return ClaudeClient.async().workingDirectory(tempDir).claudePath(cli).timeout(TIMEOUT).build();
	}

	private static List<Message> collect(Flux<Message> messages) {
		return messages.collectList().block(TIMEOUT);
	}

	private static void awaitSize(List<?> list, int size) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (list.size() < size && System.nanoTime() < deadline) {
			Thread.sleep(25);
		}
		assertThat(list).hasSizeGreaterThanOrEqualTo(size);
	}

	/**
	 * Writes a stand-in for the Claude CLI that runs the given shell body. The body
	 * decides what the CLI prints and how it ends.
	 */
	private String stubCli(String body) throws IOException {
		Path stub = Files.createTempFile(tempDir, "claude-stub-", ".sh");
		Files.writeString(stub, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
		return stub.toAbsolutePath().toString();
	}

}
