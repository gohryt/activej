/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.async.service;

import io.activej.async.function.AsyncRunnable;
import io.activej.async.function.AsyncRunnables;
import io.activej.async.function.AsyncSupplier;
import io.activej.common.initializer.WithInitializer;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.promise.Promise;
import io.activej.promise.RetryPolicy;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static io.activej.common.Checks.checkNotNull;
import static io.activej.common.Utils.nullify;
import static io.activej.promise.Promises.retry;

@SuppressWarnings("UnusedReturnValue")
public final class ReactiveTaskScheduler extends AbstractReactive
		implements ReactiveService, WithInitializer<ReactiveTaskScheduler>, ReactiveJmxBeanWithStats {
	private static final Logger logger = LoggerFactory.getLogger(ReactiveTaskScheduler.class);

	private final AsyncSupplier<Object> task;
	private final PromiseStats stats = PromiseStats.create(Duration.ofMinutes(5));

	private long initialDelay;
	private Schedule schedule;
	private RetryPolicy<Object> retryPolicy;

	private boolean abortOnError = false;

	private long lastStartTime;
	private long lastCompleteTime;

	@Override
	public void resetStats() {
		ReactiveJmxBeanWithStats.super.resetStats();
		lastException = null;
	}

	private @Nullable Exception lastException;
	private int errorCount;

	private @Nullable Duration period;
	private @Nullable Duration interval;
	private boolean enabled = true;

	@FunctionalInterface
	public interface Schedule {
		long nextTimestamp(long now, long lastStartTime, long lastCompleteTime);

		/**
		 * Schedules immediate execution.
		 */
		static Schedule immediate() {
			return (now, lastStartTime, lastCompleteTime) -> now;
		}

		/**
		 * Schedules a task after delay.
		 */
		static Schedule ofDelay(Duration delay) {
			return ofDelay(delay.toMillis());
		}

		/**
		 * Schedules a task after delay.
		 */
		static Schedule ofDelay(long delay) {
			return (now, lastStartTime, lastCompleteTime) -> now + delay;
		}

		/**
		 * Schedules a task after last complete time and next task.
		 */
		static Schedule ofInterval(Duration interval) {
			return ofInterval(interval.toMillis());
		}

		/**
		 * Schedules a task after last complete time and next task.
		 */
		static Schedule ofInterval(long interval) {
			return (now, lastStartTime, lastCompleteTime) -> lastCompleteTime + interval;
		}

		/**
		 * Schedules a task in a period of current and next task.
		 */
		static Schedule ofPeriod(Duration period) {
			return ofPeriod(period.toMillis());
		}

		/**
		 * Schedules a task in a period of current and next task.
		 */
		static Schedule ofPeriod(long period) {
			return (now, lastStartTime, lastCompleteTime) -> lastStartTime + period;
		}
	}

	private @Nullable ScheduledRunnable scheduledTask;

	private @Nullable Promise<Void> currentPromise;

	private ReactiveTaskScheduler(Reactor reactor, AsyncSupplier<?> task) {
		super(reactor);
		//noinspection unchecked
		this.task = (AsyncSupplier<Object>) task;
	}

	public static <T> ReactiveTaskScheduler create(Reactor reactor, AsyncSupplier<T> task) {
		return new ReactiveTaskScheduler(reactor, task);
	}

	public ReactiveTaskScheduler withInitialDelay(Duration initialDelay) {
		this.initialDelay = initialDelay.toMillis();
		return this;
	}

	public ReactiveTaskScheduler withSchedule(Schedule schedule) {
		this.schedule = checkNotNull(schedule);
		// for JMX:
		this.period = null;
		this.interval = null;
		return this;
	}

	public ReactiveTaskScheduler withPeriod(Duration period) {
		setPeriod(period);
		return this;
	}

	public ReactiveTaskScheduler withInterval(Duration interval) {
		setInterval(interval);
		return this;
	}

	public ReactiveTaskScheduler withRetryPolicy(RetryPolicy<?> retryPolicy) {
		//noinspection unchecked
		this.retryPolicy = (RetryPolicy<Object>) retryPolicy;
		return this;
	}

	public ReactiveTaskScheduler withAbortOnError(boolean abortOnError) {
		this.abortOnError = abortOnError;
		return this;
	}

	public ReactiveTaskScheduler withStatsHistogramLevels(int[] levels) {
		this.stats.setHistogram(levels);
		return this;
	}

	private void scheduleTask() {
		if (scheduledTask != null && scheduledTask.isCancelled())
			return;

		if (!enabled) return;

		long now = reactor.currentTimeMillis();
		long timestamp;
		if (lastStartTime == 0) {
			timestamp = now + initialDelay;
		} else {
			timestamp = schedule.nextTimestamp(now, lastStartTime, lastCompleteTime);
		}

		scheduledTask = reactor.scheduleBackground(timestamp, doCall::run);
	}

	private final AsyncRunnable doCall = AsyncRunnables.reuse(this::doCall);

	private Promise<Void> doCall() {
		lastStartTime = reactor.currentTimeMillis();
		return currentPromise = (retryPolicy == null ?
				task.get() :
				retry(task, ($, e) -> e == null || !enabled, retryPolicy))
				.whenComplete(stats.recordStats())
				.whenComplete(() -> lastCompleteTime = reactor.currentTimeMillis())
				.when(($1, $2) -> enabled,
						$ -> {
							lastException = null;
							errorCount = 0;
							scheduleTask();
						},
						e -> {
							lastException = e;
							errorCount++;
							logger.error("Retry attempt " + errorCount, e);
							if (abortOnError) {
								scheduledTask = nullify(scheduledTask, ScheduledRunnable::cancel);
								throw new RuntimeException(e);
							} else {
								scheduleTask();
							}
						})
				.toVoid();
	}

	@Override
	public @NotNull Promise<Void> start() {
		checkNotNull(schedule, "Schedule is not set");

		scheduleTask();
		return Promise.complete();
	}

	@Override
	public @NotNull Promise<Void> stop() {
		enabled = false;
		scheduledTask = nullify(scheduledTask, ScheduledRunnable::cancel);
		if (currentPromise == null) {
			return Promise.complete();
		}
		return currentPromise.map(($, e) -> null);
	}

	public void setSchedule(Schedule schedule) {
		this.schedule = checkNotNull(schedule);
		if (stats.getActivePromises() != 0 && scheduledTask != null && !scheduledTask.isCancelled()) {
			scheduledTask = nullify(scheduledTask, ScheduledRunnable::cancel);
			scheduleTask();
		}
	}

	public void setRetryPolicy(RetryPolicy<?> retryPolicy) {
		//noinspection unchecked
		this.retryPolicy = (RetryPolicy<Object>) retryPolicy;
		if (stats.getActivePromises() != 0 && scheduledTask != null && !scheduledTask.isCancelled() && lastException != null) {
			scheduledTask = nullify(scheduledTask, ScheduledRunnable::cancel);
			scheduleTask();
		}
	}

	@JmxAttribute
	public boolean isEnabled() {
		return enabled;
	}

	@JmxAttribute
	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (stats.getActivePromises() == 0) {
			if (enabled) {
				scheduleTask();
			} else {
				scheduledTask = nullify(scheduledTask, ScheduledRunnable::cancel);
			}
		}
	}

	@JmxAttribute(name = "")
	public PromiseStats getStats() {
		return stats;
	}

	@JmxAttribute
	public @Nullable Exception getLastException() {
		return lastException;
	}

	@JmxAttribute
	public long getInitialDelay() {
		return initialDelay;
	}

	@JmxAttribute
	public @Nullable Duration getPeriod() {
		return period;
	}

	@JmxAttribute
	public void setPeriod(Duration period) {
		Schedule schedule = Schedule.ofPeriod(period);
		setSchedule(schedule);
		// for JMX:
		this.period = period;
		this.interval = null;
	}

	@JmxAttribute
	public @Nullable Duration getInterval() {
		return interval;
	}

	@JmxAttribute
	public void setInterval(Duration interval) {
		setSchedule(Schedule.ofInterval(interval));
		// for JMX:
		this.period = null;
		this.interval = interval;
	}

	@JmxOperation
	public void startNow() {
		doCall.run();
	}

}