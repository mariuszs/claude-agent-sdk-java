/*
 * Copyright 2026 Mark Pollack
 */
package io.github.markpollack.claude.agent.sdk.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamingTransport#close()} terminates the CLI process tree before closing the
 * streams, so its bounded destroy is actually reachable.
 *
 * <p>
 * The order used to be reversed. {@code destroyProcessTree} is bounded — descendants
 * first, {@code destroy()}, {@code waitFor(5s)}, {@code destroyForcibly()},
 * {@code waitFor(2s)} — but it ran after {@code closeStreams()}, and closing a pipe
 * reader while another thread is blocked reading it is not bounded at all. So the bound
 * never got the chance to apply: an observed close took over three minutes, and a
 * cancelled live session left the CLI process alive through a 45-second settle and the
 * following turn, dying only when the server went away.
 *
 * <p>
 * The stub here is a CLI that reads nothing and writes nothing — the shape that provokes
 * it. No real agent CLI is discovered or invoked, so the test costs nothing. Before the
 * fix this method does not finish; after it, close returns in about a second.
 */
class StreamingTransportCloseOrderTest {

	/** A "CLI" that blocks as a single process and never speaks. */
	private Path silentCli(Path dir) throws IOException {
		Path script = dir.resolve("silent-cli.sh");
		Files.writeString(script, """
				#!/bin/sh
				# exec keeps this pid and leaves no child, so the test measures the
				# transport's close rather than a shell's signal handling.
				echo "$$" > "$(dirname "$0")/pid"
				exec sleep 86400
				""");
		script.toFile().setExecutable(true);
		return script;
	}

	private long awaitPid(Path pidFile) throws Exception {
		for (int i = 0; i < 100; i++) {
			if (Files.exists(pidFile)) {
				String raw = Files.readString(pidFile).strip();
				if (!raw.isEmpty()) {
					return Long.parseLong(raw);
				}
			}
			Thread.sleep(100);
		}
		return -1;
	}

	private boolean alive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	@Test
	void closeReturnsPromptlyAndKillsTheProcessEvenWhenTheCliNeverSpeaks(@TempDir Path dir) throws Exception {
		Path cli = silentCli(dir);
		StreamingTransport transport = new StreamingTransport(dir, Duration.ofSeconds(30), cli.toString());
		transport.startSession("block", CLIOptions.builder().build(), (message) -> {
		}, null);

		long pid = awaitPid(dir.resolve("pid"));
		assertThat(pid).as("the stub CLI started and reported its pid").isGreaterThan(0);
		assertThat(alive(pid)).as("the CLI is running before close").isTrue();

		Instant start = Instant.now();
		transport.close();
		Duration took = Duration.between(start, Instant.now());

		// The destroy path's own bound is 5s + 2s; anything near it is fine, minutes are
		// not. Before the reorder this assertion was never reached.
		assertThat(took).as("close() stayed within the destroy path's own bound").isLessThan(Duration.ofSeconds(20));

		for (int i = 0; i < 100 && alive(pid); i++) {
			Thread.sleep(100);
		}
		assertThat(alive(pid)).as("close() destroyed the CLI process").isFalse();
	}

}
