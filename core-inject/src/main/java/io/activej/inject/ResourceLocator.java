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

package io.activej.inject;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public interface ResourceLocator {
	<T> T getInstance(Key<T> key);

	default <T> T getInstance(Class<T> type) {
		return getInstance(Key.of(type));
	}

	<T> @Nullable T getInstanceOrNull(Key<T> key);

	default <T> @Nullable T getInstanceOrNull(Class<T> type) {
		return getInstanceOrNull(Key.of(type));
	}

	default <T> T getInstanceOr(Key<T> key, T defaultValue) {
		T instance = getInstanceOrNull(key);
		return instance != null ? instance : defaultValue;
	}

	default <T> T getInstanceOr(Class<T> type, T defaultValue) {
		return getInstanceOr(Key.of(type), defaultValue);
	}

	default <T> T getInstanceOrCreate(Key<T> key, Supplier<T> defaultValueFn) {
		T result = getInstanceOrNull(key);
		if (result != null) return result;
		return defaultValueFn.get();
	}

	default <T> T getInstanceOrCreate(Class<T> type, Supplier<T> defaultValueFn) {
		return getInstanceOrCreate(Key.of(type), defaultValueFn);
	}
}
