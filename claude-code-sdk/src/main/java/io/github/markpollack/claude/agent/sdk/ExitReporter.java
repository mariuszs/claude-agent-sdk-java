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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.markpollack.claude.agent.sdk.exceptions.ResultException;
import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import io.github.markpollack.claude.agent.sdk.transport.StreamingTransport;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.SystemMessage;

/**
 * Follows a session's turns so that, once the CLI's output ends, both clients report its
 * exit the same way.
 *
 * <p>
 * A client tells it when a query is about to be sent and passes it every regular message
 * the CLI sends. When the output ends, {@link #exitError(StreamingTransport)} gives the
 * error to fail the readers with, or {@code null} for a normal end.
 * </p>
 */
final class ExitReporter {

	// Whether the current turn still waits for its ResultMessage. A session that has
	// produced no result yet counts as waiting, so a CLI that fails at startup is
	// reported too.
	private final AtomicBoolean resultPending = new AtomicBoolean(true);

	// The last result, if it reported an error and nothing but a session-state change has
	// followed it. The CLI exits non-zero on purpose after an error result, and that exit
	// is then reported with the result.
	private final AtomicReference<ResultMessage> lastErrorResult = new AtomicReference<>();

	/**
	 * A query is about to be sent: the turn now waits for its result. Called before the
	 * send, so a CLI that ends at once is still seen as ending before the result.
	 */
	void querySent() {
		resultPending.set(true);
	}

	/**
	 * Takes note of a regular message from the CLI.
	 */
	void messageReceived(Message message) {
		if (message instanceof ResultMessage result) {
			resultPending.set(false);
			lastErrorResult.set(result.isError() ? result : null);
		}
		else if (!(message instanceof SystemMessage system && "session_state_changed".equals(system.subtype()))) {
			lastErrorResult.set(null);
		}
	}

	/**
	 * The error for a CLI whose output has ended, if it exited with a non-zero status.
	 *
	 * <p>
	 * While the current turn still waits for its result, this waits for the CLI to exit,
	 * however long that takes: a status that is not known yet would otherwise end the
	 * stream as if nothing had gone wrong. After the result, it takes the status known
	 * within the transport's grace period, so a CLI slow to exit after a turn that worked
	 * does not hold up the end of the stream.
	 * </p>
	 *
	 * <p>
	 * An exit after an error result yields a {@link ResultException} carrying that
	 * result. A zero status, or none because the client was closed or the CLI did not
	 * exit in time after the result, yields {@code null}, and the stream ends normally.
	 * </p>
	 * @param transport the transport whose output ended, or {@code null}
	 * @return the error to report, or {@code null} for a normal end
	 */
	TransportException exitError(StreamingTransport transport) {
		if (transport == null) {
			return null;
		}
		boolean beforeResult = resultPending.get();
		Integer exitCode = beforeResult ? transport.awaitExitCode() : transport.getExitCode();
		if (exitCode == null || exitCode == 0) {
			return null;
		}
		String tail = transport.getStderrTail();
		String stderr = tail.isEmpty() ? null : tail;
		ResultMessage errorResult = lastErrorResult.get();
		if (errorResult != null) {
			return new ResultException(errorResult, exitCode, stderr);
		}
		return new TransportException(
				beforeResult ? "Claude CLI exited before its result" : "Claude CLI exited after its result", exitCode,
				stderr);
	}

}
