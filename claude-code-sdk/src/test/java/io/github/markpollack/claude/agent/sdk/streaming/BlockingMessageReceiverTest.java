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

package io.github.markpollack.claude.agent.sdk.streaming;

import java.time.Duration;

import io.github.markpollack.claude.agent.sdk.exceptions.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * How {@link BlockingMessageReceiver} behaves once its stream has ended.
 */
class BlockingMessageReceiverTest {

	private final BlockingMessageReceiver receiver = new BlockingMessageReceiver();

	@AfterEach
	void tearDown() {
		receiver.close();
	}

	@Test
	@DisplayName("next() keeps returning null after the end, without blocking")
	void staysEndedAfterTheEnd() throws Exception {
		receiver.complete();
		assertThat(receiver.next()).isNull();

		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(receiver.next()).isNull());
	}

	@Test
	@DisplayName("next() throws the stream's error again on every later call")
	void failsAgainAfterAFailedEnd() throws Exception {
		TransportException error = new TransportException("CLI exited", 3, null);
		receiver.completeWithError(error);
		assertThatThrownBy(receiver::next).isSameAs(error);

		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(receiver::next).isSameAs(error));
	}

}
