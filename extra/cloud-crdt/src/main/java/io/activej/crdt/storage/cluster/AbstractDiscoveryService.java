package io.activej.crdt.storage.cluster;

import io.activej.common.exception.MalformedDataException;
import io.activej.crdt.storage.CrdtStorage;
import io.activej.rpc.client.sender.RpcStrategy;
import io.activej.types.TypeT;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

import static io.activej.fs.util.JsonUtils.fromJson;

public abstract class AbstractDiscoveryService<D extends AbstractDiscoveryService<D>> implements DiscoveryService {
	protected static final TypeT<List<RendezvousPartitionGroup<PartitionId>>> PARTITION_GROUPS_TYPE = new TypeT<>() {};

	protected @Nullable Function<PartitionId, @NotNull RpcStrategy> rpcProvider;
	protected @Nullable Function<PartitionId, @NotNull CrdtStorage<?, ?>> crdtProvider;

	public D withCrdtProvider(Function<PartitionId, CrdtStorage<?, ?>> crdtProvider) {
		this.crdtProvider = crdtProvider;
		//noinspection unchecked
		return (D) this;
	}

	public D withRpcProvider(Function<PartitionId, RpcStrategy> rpcProvider) {
		this.rpcProvider = rpcProvider;
		//noinspection unchecked
		return (D) this;
	}

	protected final RendezvousPartitionScheme<PartitionId> parseScheme(byte[] bytes) throws MalformedDataException {
		List<RendezvousPartitionGroup<PartitionId>> partitionGroups = fromJson(PARTITION_GROUPS_TYPE, bytes);
		RendezvousPartitionScheme<PartitionId> scheme = RendezvousPartitionScheme.create(partitionGroups)
				.withPartitionIdGetter(PartitionId::getId);

		if (rpcProvider != null) scheme.withRpcProvider(rpcProvider);
		if (crdtProvider != null) scheme.withCrdtProvider(crdtProvider);

		return scheme;
	}
}
