package org.apereo.cas.monitor;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.hazelcast.instance.HazelcastInstanceProxy;
import com.hazelcast.memory.MemoryStats;
import com.hazelcast.monitor.LocalMapStats;
import org.apereo.cas.configuration.model.support.hazelcast.HazelcastTicketRegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This is {@link HazelcastMonitor}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class HazelcastMonitor extends AbstractCacheMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastMonitor.class);

    private static int clusterSize;

    public HazelcastMonitor(final String clusterName) {
        super(HazelcastMonitor.class.getSimpleName());
        final HazelcastInstanceProxy instance = (HazelcastInstanceProxy) Hazelcast.getHazelcastInstanceByName(clusterName);
        getClusterSize(instance);
    }

    @Override
    protected CacheStatistics[] getStatistics() {
        final List<CacheStatistics> statsList = new ArrayList<>();
        final HazelcastTicketRegistryProperties hz = casProperties.getTicket().getRegistry().getHazelcast();
        LOGGER.debug("Locating hazelcast instance [{}]...", hz.getCluster().getInstanceName());
        final HazelcastInstanceProxy instance = (HazelcastInstanceProxy) Hazelcast.getHazelcastInstanceByName(hz.getCluster().getInstanceName());
        getClusterSize(instance);
        final boolean isMaster = instance.getOriginal().node.isMaster();
        instance.getConfig().getMapConfigs().keySet().forEach(key -> {
            final IMap map = instance.getMap(key);
            LOGGER.debug("Starting to collect hazelcast statistics for map [{}] identified by key [{}]...", map, key);
            statsList.add(new HazelcastStatistics(map, clusterSize, isMaster));

        });
        return statsList.toArray(new CacheStatistics[statsList.size()]);
    }

    private void getClusterSize(HazelcastInstanceProxy instance) {
        Runnable callForSize = new Runnable() {
            @Override
            public void run() {
                HazelcastMonitor.clusterSize = instance.getOriginal().node.getClusterService().getSize();
            }
        };
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(callForSize);
    }

    /**
     * The type Hazelcast statistics.
     */
    public static class HazelcastStatistics implements CacheStatistics {
        private static final int PERCENTAGE_VALUE = 100;
        private static final double BYTES_PER_MB = 1048510.0;
        private static final double BYTEST_PER_KB = 1024.0;

        private final IMap map;
        private final int clusterSize;
        private boolean isMaster;
        private MemoryStats memoryStats;

        protected HazelcastStatistics(final IMap map, final int clusterSize, final boolean isMaster) {
            this.map = map;
            this.clusterSize = clusterSize;
            this.isMaster = isMaster;
        }

        @Override
        public long getSize() {
            return this.map.size();
        }

        @Override
        public long getCapacity() {
            return this.map.getLocalMapStats() != null ? this.map.getLocalMapStats().total() : 0;

        }

        @Override
        public long getEvictions() {
            if (this.map.getLocalMapStats() != null && this.map.getLocalMapStats().getNearCacheStats() != null) {
                return this.map.getLocalMapStats().getNearCacheStats().getMisses();
            }
            return 0;
        }


        @Override
        public String getName() {
            return this.map.getName();
        }

        @Override
        public int getPercentFree() {
            final long capacity = getCapacity();
            if (capacity == 0) {
                return -1;
            }
            return (int) ((capacity - getSize()) * PERCENTAGE_VALUE / capacity);
        }

        @Override
        public int getNumberOfMembers() {
            return clusterSize;
        }

        @Override
        public boolean isMaster() {
            return isMaster;
        }

        @Override
        public long getMemoryCost() {
            return map.getLocalMapStats().getHeapCost();
        }

        @Override
        public void toString(final StringBuilder builder) {
            final LocalMapStats localMapStats = map.getLocalMapStats();
             builder.append("\n\t  ")
                    .append("Map: "+map.getName())
                    .append(" - ")
                    .append("Size: ")
                    .append(formatMemory(localMapStats.getHeapCost()))
                    .append("\n\t    ")
                    .append("Local entry count: ")
                    .append(localMapStats.getOwnedEntryCount())
                    .append("\t    ")
                    .append("Local entry memory:")
                    .append(formatMemory(localMapStats.getOwnedEntryMemoryCost()))
                    .append("\n\t    ")
                    .append("Backup entry count: ")
                    .append(localMapStats.getBackupEntryCount())
                     .append("\t    ")
                     .append("Backup entry memory: ")
                     .append(formatMemory(localMapStats.getBackupEntryMemoryCost()))
                     .append("\n\t    ")
                    .append("Max get latency: ")
                    .append(localMapStats.getMaxGetLatency())
                     .append("\t\t    ")
                    .append("Max put latency: ")
                    .append(localMapStats.getMaxPutLatency());


            if (localMapStats.getNearCacheStats() != null) {
                builder.append(", Misses: ")
                        .append(localMapStats.getNearCacheStats().getMisses());
            }
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            this.toString(builder);
            return builder.toString();
        }

        public String formatMemory(long mem) {
            if (mem < BYTEST_PER_KB) {
                return String.format(mem+"B");
            }
            if (mem < BYTES_PER_MB) {
                return String.format("%.2fKB", mem / BYTEST_PER_KB);
            }
            return String.format("%.2fMB", mem / BYTES_PER_MB);
        }
    }


}
