package io.activej.cube;

import io.activej.csp.process.frame.FrameFormat;
import io.activej.csp.process.frame.FrameFormats;
import io.activej.cube.CubeStructure.AggregationConfig;
import io.activej.cube.aggregation.AggregationChunkStorage;
import io.activej.cube.aggregation.IAggregationChunkStorage;
import io.activej.cube.aggregation.predicate.AggregationPredicate;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffScheme;
import io.activej.cube.ot.ProtoCubeDiff;
import io.activej.cube.service.CubeConsolidationController;
import io.activej.datastream.consumer.StreamConsumers;
import io.activej.datastream.supplier.StreamSuppliers;
import io.activej.etl.LogDiff;
import io.activej.etl.LogProcessor;
import io.activej.etl.LogState;
import io.activej.fs.FileSystem;
import io.activej.multilog.IMultilog;
import io.activej.multilog.Multilog;
import io.activej.ot.StateManager;
import io.activej.serializer.SerializerFactory;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static io.activej.common.collection.CollectorUtils.entriesToLinkedHashMap;
import static io.activej.cube.CubeStructure.AggregationConfig.id;
import static io.activej.cube.TestUtils.runProcessLogs;
import static io.activej.cube.TestUtils.stubChunkIdGenerator;
import static io.activej.cube.aggregation.fieldtype.FieldTypes.*;
import static io.activej.cube.aggregation.measure.Measures.sum;
import static io.activej.cube.aggregation.predicate.AggregationPredicates.alwaysTrue;
import static io.activej.cube.aggregation.predicate.AggregationPredicates.gt;
import static io.activej.multilog.LogNamingScheme.NAME_PARTITION_REMAINDER_SEQ;
import static io.activej.promise.TestUtils.await;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CubeRemovingOfIrrelevantChunksTest extends CubeTestBase {
	private static final int numberMin = 0;
	private static final int numberMax = 100;

	private static final int dateMin = (int) LocalDate.of(2020, Month.JANUARY, 1).toEpochDay();
	private static final int dateMax = (int) LocalDate.of(2021, Month.JANUARY, 1).toEpochDay();
	private static final LocalDate LOWER_DATE_BOUNDARY = LocalDate.of(2020, Month.JULY, 31);
	private static final int LOWER_DATE_BOUNDARY_DAYS = (int) LOWER_DATE_BOUNDARY.toEpochDay();
	private static final AggregationPredicate DATE_PREDICATE = gt("date", LOWER_DATE_BOUNDARY);

	private IAggregationChunkStorage chunkStorage;
	private AggregationConfig dateAggregation;
	private AggregationConfig advertiserDateAggregation;
	private AggregationConfig campaignBannerDateAggregation;

	@Before
	public void before() throws Exception {
		Path aggregationsDir = temporaryFolder.newFolder().toPath();
		Path logsDir = temporaryFolder.newFolder().toPath();

		FileSystem fs = FileSystem.builder(reactor, EXECUTOR, aggregationsDir)
			.withTempDir(Files.createTempDirectory(""))
			.build();
		await(fs.start());
		FrameFormat frameFormat = FrameFormats.lz4();
		chunkStorage = AggregationChunkStorage.create(reactor, stubChunkIdGenerator(), frameFormat, fs);

		dateAggregation = id("date")
			.withDimensions("date")
			.withMeasures("impressions", "clicks", "conversions", "revenue");

		advertiserDateAggregation = id("advertiser-date")
			.withDimensions("advertiser", "date")
			.withMeasures("impressions", "clicks", "conversions", "revenue");

		campaignBannerDateAggregation = id("campaign-banner-date")
			.withDimensions("campaign", "banner", "date")
			.withMeasures("impressions", "clicks", "conversions", "revenue");

		CubeStructure basicCubeStructure = builderOfBasicCubeStructure()
			.withAggregation(dateAggregation)
			.withAggregation(advertiserDateAggregation)
			.withAggregation(campaignBannerDateAggregation)
			.build();

		StateManager<LogDiff<CubeDiff>, LogState<CubeDiff, CubeState>> stateManager = stateManagerFactory.create(basicCubeStructure, description);

		FileSystem fileSystem = FileSystem.create(reactor, EXECUTOR, logsDir);
		await(fileSystem.start());
		IMultilog<LogItem> multilog = Multilog.create(reactor,
			fileSystem,
			frameFormat,
			SerializerFactory.defaultInstance().create(CLASS_LOADER, LogItem.class),
			NAME_PARTITION_REMAINDER_SEQ);

		CubeExecutor cubeExecutor = CubeExecutor.create(reactor, basicCubeStructure, EXECUTOR, CLASS_LOADER, chunkStorage);

		LogProcessor<LogItem, ProtoCubeDiff, CubeDiff> logOTProcessor = LogProcessor.create(reactor,
			multilog,
			cubeExecutor.logStreamConsumer(LogItem.class),
			"testlog",
			List.of("partitionA"),
			stateManager);

		// Save and aggregate logs
		List<LogItem> allLogItems = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			List<LogItem> listOfRandomLogItems = LogItem.getListOfRandomLogItems(100);
			if (i == 0) {
				rangeLogItemsOutOfPredicate(listOfRandomLogItems);
			} else {
				randomRangeLogItems(listOfRandomLogItems);
			}

			await(StreamSuppliers.ofIterable(listOfRandomLogItems).streamTo(
				StreamConsumers.ofPromise(multilog.write("partitionA"))));

			runProcessLogs(chunkStorage, stateManager, logOTProcessor);
			allLogItems.addAll(listOfRandomLogItems);
		}

		CubeReporting cubeReporting = CubeReporting.create(stateManager, basicCubeStructure, cubeExecutor);
		List<LogItem> logItems = await(cubeReporting.queryRawStream(List.of("date"), List.of("clicks"), alwaysTrue(),
				LogItem.class, CLASS_LOADER)
			.toList());

		// Aggregate manually
		Map<Integer, Long> map = new HashMap<>();
		aggregateToMap(map, allLogItems);

		assertEquals(map, logItems.stream().collect(toMap(r -> r.date, r -> r.clicks)));
	}

	@Test
	public void test() throws Exception {
		CubeStructure cubeStructure = builderOfBasicCubeStructure()
			.withAggregation(dateAggregation.withPredicate(DATE_PREDICATE))
			.withAggregation(advertiserDateAggregation.withPredicate(DATE_PREDICATE))
			.withAggregation(campaignBannerDateAggregation.withPredicate(DATE_PREDICATE))
			.build();
		StateManager<LogDiff<CubeDiff>, LogState<CubeDiff, CubeState>> stateManager = stateManagerFactory.createUninitialized(cubeStructure, description);
		stateManagerFactory.start(stateManager);

		CubeExecutor cubeExecutor = CubeExecutor.create(reactor, cubeStructure, EXECUTOR, CLASS_LOADER, chunkStorage);
		CubeConsolidator cubeConsolidator = CubeConsolidator.create(stateManager, cubeExecutor);

		CubeConsolidationController<LogDiff<CubeDiff>> consolidationController =
			CubeConsolidationController.create(reactor, CubeDiffScheme.ofLogDiffs(), cubeConsolidator, cubeStructure);

		Map<String, Integer> chunksBefore = getChunksByAggregation(stateManager);
		await(consolidationController.cleanupIrrelevantChunks());

		Map<String, Integer> chunksAfter = getChunksByAggregation(stateManager);

		for (Map.Entry<String, Integer> afterEntry : chunksAfter.entrySet()) {
			String key = afterEntry.getKey();
			Integer before = chunksBefore.get(key);
			Integer after = afterEntry.getValue();
			assertTrue(after < before);
			System.out.println("Removed " + (before - after) + " chunks form aggregation'" + key + '\'');
		}
	}

	private static Map<String, Integer> getChunksByAggregation(StateManager<LogDiff<CubeDiff>, LogState<CubeDiff, CubeState>> stateManager) {
		return stateManager.query(state ->
			state.getDataState().getAggregationStates().entrySet().stream()
				.collect(entriesToLinkedHashMap(
					Function.identity(),
					AggregationState::getChunksSize))
		);
	}

	private CubeStructure.Builder builderOfBasicCubeStructure() {
		return CubeStructure.builder()
			.withDimension("date", ofLocalDate())
			.withDimension("advertiser", ofInt())
			.withDimension("campaign", ofInt())
			.withDimension("banner", ofInt())
			.withRelation("campaign", "advertiser")
			.withRelation("banner", "campaign")
			.withMeasure("impressions", sum(ofLong()))
			.withMeasure("clicks", sum(ofLong()))
			.withMeasure("conversions", sum(ofLong()))
			.withMeasure("revenue", sum(ofDouble()));
	}

	private void randomRangeLogItems(List<LogItem> logItems) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		boolean breakPredicate = random.nextBoolean();
		int sameAdvertiser = random.nextBoolean() ? random.nextInt(numberMin, numberMax + 1) : -1;
		int sameCampaign = random.nextBoolean() ? random.nextInt(numberMin, numberMax + 1) : -1;
		int sameBanner = random.nextBoolean() ? random.nextInt(numberMin, numberMax + 1) : -1;
		for (LogItem logItem : logItems) {
			logItem.advertiser = sameAdvertiser == -1 ? random.nextInt(numberMin, numberMax + 1) : sameAdvertiser;
			logItem.campaign = sameCampaign == -1 ? random.nextInt(numberMin, numberMax + 1) : sameCampaign;
			logItem.banner = sameBanner == -1 ? random.nextInt(numberMin, numberMax + 1) : sameBanner;
			logItem.date = (int) random.nextLong(dateMin, breakPredicate ? LOWER_DATE_BOUNDARY_DAYS + 1 : dateMax + 1);
		}
	}

	private void rangeLogItemsOutOfPredicate(List<LogItem> logItems) {
		for (LogItem logItem : logItems) {
			logItem.advertiser = 0;
			logItem.campaign = 0;
			logItem.banner = 0;
			logItem.date = dateMin;
		}
	}

	private void aggregateToMap(Map<Integer, Long> map, List<LogItem> logItems) {
		for (LogItem logItem : logItems) {
			int date = logItem.date;
			long clicks = logItem.clicks;
			if (map.get(date) == null) {
				map.put(date, clicks);
			} else {
				Long clicksForDate = map.get(date);
				map.put(date, clicksForDate + clicks);
			}
		}
	}
}
