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

package io.github.markpollack.claude.agent.sdk.exceptions;

import io.github.markpollack.claude.agent.sdk.types.ResultMessage;

/**
 * Thrown when the CLI exits with a non-zero status after reporting an error result.
 *
 * <p>
 * The CLI ends a failed run ({@code error_max_turns}, {@code error_during_execution}, an
 * API error) with a {@link ResultMessage} whose {@code isError()} is true, and then exits
 * non-zero on purpose. This exception replaces the bare exit status for that case: its
 * message says why the run failed, and {@link #getResult()} holds the result, so a caller
 * can branch on its {@code subtype()} or {@code errors()} without parsing text. Being a
 * {@link TransportException}, it still carries the exit status, and existing handlers of
 * that type keep working. The Python SDK raises {@code ResultError} for the same case.
 * </p>
 */
public class ResultException extends TransportException {

	private final transient ResultMessage result;

	public ResultException(ResultMessage result, Integer exitCode, String stderr) {
		super("Claude CLI returned an error result: " + describe(result), exitCode, stderr);
		this.result = result;
	}

	/**
	 * Gets the error result the CLI reported before it exited.
	 * @return the result message
	 */
	public ResultMessage getResult() {
		return result;
	}

	/**
	 * Picks the most telling text of an error result: its {@code errors}, then its
	 * {@code result} text (where an API failure reported as {@code success} puts its
	 * "API Error: ..." prose), then a subtype other than {@code success}.
	 */
	private static String describe(ResultMessage result) {
		if (!result.errors().isEmpty()) {
			return String.join("; ", result.errors());
		}
		if (result.result() != null && !result.result().isBlank()) {
			return result.result().strip();
		}
		if (result.subtype() != null && !result.subtype().isEmpty() && !"success".equals(result.subtype())) {
			return result.subtype();
		}
		return "unknown error";
	}

}
