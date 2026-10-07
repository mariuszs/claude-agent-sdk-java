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

/**
 * Exception thrown when the Claude CLI cannot be started because there is no runnable CLI
 * where the SDK looked: no file at the configured path, no command of that name on
 * {@code PATH}, or a file that is not executable. The process never ran, so
 * {@link #getExitCode()} is {@code null}.
 *
 * <p>
 * Corresponds to {@code CLINotFoundError} in the Python SDK.
 */
public class CLINotFoundException extends TransportException {

	public CLINotFoundException(String message) {
		super(message);
	}

	public CLINotFoundException(String message, Throwable cause) {
		super(message, cause);
	}

}
