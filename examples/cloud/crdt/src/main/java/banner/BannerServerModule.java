package banner;

import io.activej.common.initializer.Initializer;
import io.activej.crdt.function.CrdtFunction;
import io.activej.crdt.hash.CrdtMap;
import io.activej.crdt.hash.JavaCrdtMap;
import io.activej.crdt.primitives.GSet;
import io.activej.crdt.storage.CrdtStorage;
import io.activej.crdt.storage.local.CrdtStorageMap;
import io.activej.crdt.wal.InMemoryWriteAheadLog;
import io.activej.crdt.wal.WriteAheadLog;
import io.activej.eventloop.Eventloop;
import io.activej.inject.Key;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.rpc.server.RpcRequestHandler;
import io.activej.service.ServiceGraphModuleSettings;

import java.util.Map;

import static banner.BannerCommands.*;

public class BannerServerModule extends AbstractModule {

	@Provides
	@SuppressWarnings("ConstantConditions")
	Map<Class<?>, RpcRequestHandler<?, ?>> handlers(
			CrdtMap<Long, GSet<Integer>> map,
			WriteAheadLog<Long, GSet<Integer>> writeAheadLog
	) {
		return Map.of(
				PutRequest.class, (RpcRequestHandler<PutRequest, PutResponse>) request -> {
					long userId = request.getUserId();
					GSet<Integer> bannerIds = GSet.of(request.getBannerIds());
					return writeAheadLog.put(userId, bannerIds)
							.then(() -> map.put(userId, bannerIds))
							.map($ -> PutResponse.INSTANCE);
				},
				GetRequest.class, (RpcRequestHandler<GetRequest, GetResponse>) request1 ->
						map.get(request1.getUserId())
								.map(GetResponse::new), IsBannerSeenRequest.class, (RpcRequestHandler<IsBannerSeenRequest, Boolean>) request2 ->
						map.get(request2.getUserId())
								.map(bannerIds1 -> bannerIds1 != null && bannerIds1.contains(request2.getBannerId())));
	}

	@Provides
	CrdtMap<Long, GSet<Integer>> map(Eventloop eventloop, CrdtStorage<Long, GSet<Integer>> storage) {
		return new JavaCrdtMap<>(eventloop, GSet::merge, storage);
	}

	@Provides
	CrdtFunction<GSet<Integer>> function() {
		return new CrdtFunction<GSet<Integer>>() {
			@Override
			public GSet<Integer> merge(GSet<Integer> first, long firstTimestamp, GSet<Integer> second, long secondTimestamp) {
				return first.merge(second);
			}

			@Override
			public GSet<Integer> extract(GSet<Integer> state, long timestamp) {
				return state;
			}
		};
	}

	@Provides
	WriteAheadLog<Long, GSet<Integer>> writeAheadLog(Eventloop eventloop, CrdtFunction<GSet<Integer>> function, CrdtStorage<Long, GSet<Integer>> storage) {
		return InMemoryWriteAheadLog.create(eventloop, function, storage);
	}

	@Provides
	CrdtStorage<Long, GSet<Integer>> storage(Eventloop eventloop, CrdtFunction<GSet<Integer>> function) {
		return CrdtStorageMap.create(eventloop, function);
	}

	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> configureServiceGraph() {
		// add logical dependency so that service graph starts CrdtMap only after it has started the WriteAheadLog
		return settings -> settings.addDependency(new Key<CrdtMap<Long, GSet<Integer>>>() {}, new Key<WriteAheadLog<Long, GSet<Integer>>>() {});
	}
}
