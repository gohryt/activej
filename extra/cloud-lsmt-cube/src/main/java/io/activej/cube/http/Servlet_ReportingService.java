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

package io.activej.cube.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.codegen.DefiningClassLoader;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.time.Stopwatch;
import io.activej.cube.AsyncCube;
import io.activej.cube.CubeQuery;
import io.activej.cube.exception.QueryException;
import io.activej.http.*;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.common.Utils.not;
import static io.activej.cube.Utils.fromJson;
import static io.activej.cube.Utils.toJsonBuf;
import static io.activej.cube.http.Utils.*;
import static io.activej.http.HttpHeaderValue.ofContentType;
import static io.activej.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.http.HttpMethod.GET;
import static java.util.stream.Collectors.toList;

public final class Servlet_ReportingService extends Servlet_WithStats {
	private static final Logger logger = LoggerFactory.getLogger(Servlet_ReportingService.class);

	private final AsyncCube cube;
	private JsonCodec_QueryResult queryResultCodec;
	private JsonCodec_AggregationPredicate aggregationPredicateCodec;

	private DefiningClassLoader classLoader = DefiningClassLoader.create();

	private Servlet_ReportingService(Reactor reactor, AsyncCube cube) {
		super(reactor);
		this.cube = cube;
	}

	public static Servlet_ReportingService create(Reactor reactor, AsyncCube cube) {
		return builder(reactor, cube).build();
	}

	public static Servlet_Routing createRootServlet(Reactor reactor, AsyncCube cube) {
		return createRootServlet(
				Servlet_ReportingService.create(reactor, cube));
	}

	public static Servlet_Routing createRootServlet(Servlet_ReportingService reportingServiceServlet) {
		return Servlet_Routing.create(reportingServiceServlet.reactor)
				.map(GET, "/", reportingServiceServlet);
	}

	public static Builder builder(Reactor reactor, AsyncCube cube) {
		return new Servlet_ReportingService(reactor, cube).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, Servlet_ReportingService> {
		private Builder() {}

		public Builder withClassLoader(DefiningClassLoader classLoader) {
			checkNotBuilt(this);
			Servlet_ReportingService.this.classLoader = classLoader;
			return this;
		}

		@Override
		protected Servlet_ReportingService doBuild() {
			return Servlet_ReportingService.this;
		}
	}

	private JsonCodec_AggregationPredicate getAggregationPredicateCodec() {
		if (aggregationPredicateCodec == null) {
			aggregationPredicateCodec = JsonCodec_AggregationPredicate.create(cube.getAttributeTypes(), cube.getMeasureTypes());
		}
		return aggregationPredicateCodec;
	}

	private JsonCodec_QueryResult getQueryResultCodec() {
		if (queryResultCodec == null) {
			queryResultCodec = JsonCodec_QueryResult.create(classLoader, cube.getAttributeTypes(), cube.getMeasureTypes());
		}
		return queryResultCodec;
	}

	@Override
	public Promise<HttpResponse> doServe(HttpRequest httpRequest) {
		logger.info("Received request: {}", httpRequest);
		try {
			Stopwatch totalTimeStopwatch = Stopwatch.createStarted();
			CubeQuery cubeQuery = parseQuery(httpRequest);
			return cube.query(cubeQuery)
					.map(queryResult -> {
						Stopwatch resultProcessingStopwatch = Stopwatch.createStarted();
						ByteBuf jsonBuf = toJsonBuf(getQueryResultCodec(), queryResult);
						HttpResponse httpResponse = createResponse(jsonBuf);
						logger.info("Processed request {} ({}) [totalTime={}, jsonConstruction={}]", httpRequest,
								cubeQuery, totalTimeStopwatch, resultProcessingStopwatch);
						return httpResponse;
					});
		} catch (QueryException e) {
			logger.error("Query exception: " + httpRequest, e);
			return Promise.of(createErrorResponse(e.getMessage()));
		} catch (MalformedDataException e) {
			logger.error("Parse exception: " + httpRequest, e);
			return Promise.of(createErrorResponse(e.getMessage()));
		}
	}

	private static HttpResponse createResponse(ByteBuf body) {
		HttpResponse response = HttpResponse.ok200();
		response.addHeader(CONTENT_TYPE, ofContentType(ContentType.of(MediaTypes.JSON, StandardCharsets.UTF_8)));
		response.setBody(body);
		response.addHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		return response;
	}

	private static HttpResponse createErrorResponse(String body) {
		HttpResponse response = HttpResponse.ofCode(400);
		response.addHeader(CONTENT_TYPE, ofContentType(ContentType.of(MediaTypes.PLAIN_TEXT, StandardCharsets.UTF_8)));
		response.setBody(wrapUtf8(body));
		response.addHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		return response;
	}

	private static final Pattern SPLITTER = Pattern.compile(",");

	private static List<String> split(String input) {
		return SPLITTER.splitAsStream(input)
				.map(String::trim)
				.filter(not(String::isEmpty))
				.collect(toList());
	}

	public CubeQuery parseQuery(HttpRequest request) throws MalformedDataException {
		CubeQuery.Builder queryBuilder = CubeQuery.builder();

		String parameter;
		parameter = request.getQueryParameter(ATTRIBUTES_PARAM);
		if (parameter != null)
			queryBuilder.withAttributes(split(parameter));

		parameter = request.getQueryParameter(MEASURES_PARAM);
		if (parameter != null)
			queryBuilder.withMeasures(split(parameter));

		parameter = request.getQueryParameter(WHERE_PARAM);
		if (parameter != null)
			queryBuilder.withWhere(fromJson(getAggregationPredicateCodec(), parameter));

		parameter = request.getQueryParameter(SORT_PARAM);
		if (parameter != null)
			queryBuilder.withOrderings(parseOrderings(parameter));

		parameter = request.getQueryParameter(HAVING_PARAM);
		if (parameter != null)
			queryBuilder.withHaving(fromJson(getAggregationPredicateCodec(), parameter));

		parameter = request.getQueryParameter(LIMIT_PARAM);
		if (parameter != null)
			queryBuilder.withLimit(parseNonNegativeInteger(parameter));

		parameter = request.getQueryParameter(OFFSET_PARAM);
		if (parameter != null)
			queryBuilder.withOffset(parseNonNegativeInteger(parameter));

		parameter = request.getQueryParameter(REPORT_TYPE_PARAM);
		if (parameter != null)
			queryBuilder.withReportType(parseReportType(parameter));

		return queryBuilder.build();
	}

}