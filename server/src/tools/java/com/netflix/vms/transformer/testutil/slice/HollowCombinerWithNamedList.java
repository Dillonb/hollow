package com.netflix.vms.transformer.testutil.slice;

import com.netflix.hollow.HollowObjectSchema;
import com.netflix.hollow.combine.HollowCombiner;
import com.netflix.hollow.read.engine.HollowReadStateEngine;
import com.netflix.hollow.read.engine.PopulatedOrdinalListener;
import com.netflix.hollow.read.engine.object.HollowObjectTypeReadState;
import com.netflix.hollow.util.SimultaneousExecutor;
import com.netflix.hollow.write.HollowMapWriteRecord;
import com.netflix.hollow.write.HollowObjectWriteRecord;
import com.netflix.hollow.write.HollowSetWriteRecord;
import com.netflix.hollow.write.HollowWriteStateEngine;
import com.netflix.type.ISOCountry;
import com.netflix.type.NFCountry;
import com.netflix.vms.generated.notemplate.EpisodeHollow;
import com.netflix.vms.generated.notemplate.MapOfStringsToSetOfEpisodeHollow;
import com.netflix.vms.generated.notemplate.MapOfStringsToSetOfVPersonHollow;
import com.netflix.vms.generated.notemplate.MapOfStringsToSetOfVideoHollow;
import com.netflix.vms.generated.notemplate.NamedCollectionHolderHollow;
import com.netflix.vms.generated.notemplate.SetOfEpisodeHollow;
import com.netflix.vms.generated.notemplate.SetOfVPersonHollow;
import com.netflix.vms.generated.notemplate.SetOfVideoHollow;
import com.netflix.vms.generated.notemplate.StringsHollow;
import com.netflix.vms.generated.notemplate.VMSRawHollowAPI;
import com.netflix.vms.generated.notemplate.VPersonHollow;
import com.netflix.vms.generated.notemplate.VideoHollow;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HollowCombinerWithNamedList {
    public static final String NAMEDLIST_TYPE_STATE_NAME = "NamedCollectionHolder";

    private final HollowCombiner combiner;
    private final HollowReadStateEngine inputs[];

    protected final ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String, Set<Integer>>> combinedVideoLists;
    protected final ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String, Set<Integer>>> combinedPersonLists;
    protected final ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String, Set<Integer>>> combinedEpisodeLists;

    protected HollowCombinerWithNamedList(HollowWriteStateEngine output, HollowReadStateEngine... inputs) {
        this.inputs = inputs;
        this.combiner = new HollowCombiner(output, inputs);
        combiner.addIgnoredTypes(HollowCombinerWithNamedList.NAMEDLIST_TYPE_STATE_NAME);

        this.combinedVideoLists = new ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String,Set<Integer>>>();
        this.combinedPersonLists = new ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String,Set<Integer>>>();
        this.combinedEpisodeLists = new ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String,Set<Integer>>>();
    }

    public void combine() throws Exception {
        combiner.combine();
        buildPOJOLists();
        writePOJOListsToOutput(combiner.getCombinedStateEngine());
    }

    public HollowWriteStateEngine getCombinedStateEngine() {
        return combiner.getCombinedStateEngine();
    }


    protected void buildPOJOLists() throws Exception {
        SimultaneousExecutor executor = new SimultaneousExecutor();

        for (HollowReadStateEngine input : inputs) {
            VMSRawHollowAPI api = new VMSRawHollowAPI(input);
            HollowObjectTypeReadState namedCollectionHolderState = (HollowObjectTypeReadState) input.getTypeState(NAMEDLIST_TYPE_STATE_NAME);

            PopulatedOrdinalListener listener = namedCollectionHolderState.getListener(PopulatedOrdinalListener.class);
            BitSet populatedOrdinals = listener.getPopulatedOrdinals();

            int ordinal = populatedOrdinals.nextSetBit(0);
            while (ordinal != -1) {
                traverseShardNamedCollectionHolder(api, ordinal, executor);

                ordinal = populatedOrdinals.nextSetBit(ordinal + 1);
            }
        }

        executor.awaitSuccessfulCompletion();
    }

    private void traverseShardNamedCollectionHolder(VMSRawHollowAPI api, int ordinal, SimultaneousExecutor executor) {
        final NamedCollectionHolderHollow holder = api.getNamedCollectionHolderHollow(ordinal);
        final String countryId = holder._getCountry()._getId();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                ConcurrentHashMap<String, Set<Integer>> countryMap = getCountryMap(combinedVideoLists, countryId);
                MapOfStringsToSetOfVideoHollow videoListMaps = holder._getVideoListMap();
                for (Map.Entry<StringsHollow, SetOfVideoHollow> entry : videoListMaps.entrySet()) {
                    Set<Integer> namedSet = getNamedSet(countryMap, entry.getKey()._getValue());
                    synchronized (namedSet) {
                        for (VideoHollow video : entry.getValue()) {
                            namedSet.add(video._getValueBoxed());
                        }
                    }
                }
            }
        });

        executor.execute(new Runnable() {
            @Override
            public void run() {
                ConcurrentHashMap<String, Set<Integer>> countryMap = getCountryMap(combinedEpisodeLists, countryId);
                MapOfStringsToSetOfEpisodeHollow episodeListMaps = holder._getEpisodeListMap();
                for (Map.Entry<StringsHollow, SetOfEpisodeHollow> entry : episodeListMaps.entrySet()) {
                    Set<Integer> namedSet = getNamedSet(countryMap, entry.getKey()._getValue());
                    synchronized (namedSet) {
                        for (EpisodeHollow ep : entry.getValue()) {
                            namedSet.add(ep._getIdBoxed());
                        }
                    }
                }
            }
        });

        executor.execute(new Runnable() {
            @Override
            public void run() {
                ConcurrentHashMap<String, Set<Integer>> countryMap = getCountryMap(combinedPersonLists, countryId);
                MapOfStringsToSetOfVPersonHollow personListMaps = holder._getPersonListMap();
                for (Map.Entry<StringsHollow, SetOfVPersonHollow> entry : personListMaps.entrySet()) {
                    Set<Integer> namedSet = getNamedSet(countryMap, entry.getKey()._getValue());
                    synchronized (namedSet) {
                        for (VPersonHollow person : entry.getValue()) {
                            namedSet.add(person._getIdBoxed());
                        }
                    }
                }
            }
        });

    }

    private ConcurrentHashMap<String, Set<Integer>> getCountryMap(ConcurrentHashMap<ISOCountry, ConcurrentHashMap<String, Set<Integer>>> combinedMaps, String countryId) {
        NFCountry country = NFCountry.findInstance(countryId);

        ConcurrentHashMap<String, Set<Integer>> map = combinedMaps.get(country);
        if (map == null) {
            map = new ConcurrentHashMap<String, Set<Integer>>();
            ConcurrentHashMap<String, Set<Integer>> existingMap = combinedMaps.putIfAbsent(country, map);
            if (existingMap != null)
                map = existingMap;
        }

        return map;
    }

    private Set<Integer> getNamedSet(ConcurrentHashMap<String, Set<Integer>> countryMaps, String listName) {
        Set<Integer> set = countryMaps.get(listName);

        if (set == null) {
            set = new HashSet<Integer>();
            Set<Integer> existingSet = countryMaps.putIfAbsent(listName, set);
            if (existingSet != null)
                set = existingSet;
        }

        return set;
    }

    private void writePOJOListsToOutput(HollowWriteStateEngine output) throws Exception {
        SimultaneousExecutor executor = new SimultaneousExecutor();

        final int emptyResourceIdMapOrdinal = writeEmptyResourceIdMapToOutput(output);

        for(final Entry<ISOCountry, ConcurrentHashMap<String, Set<Integer>>> entry : combinedVideoLists.entrySet()) {
            final ISOCountry country = entry.getKey();

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    ConcurrentHashMap<String, Set<Integer>> videoLists = entry.getValue();
                    ConcurrentHashMap<String, Set<Integer>> personLists = combinedPersonLists.get(country);
                    ConcurrentHashMap<String, Set<Integer>> episodeLists = combinedEpisodeLists.get(country);

                    HollowObjectWriteRecord holderRec = new HollowObjectWriteRecord((HollowObjectSchema) output.getSchema("NamedCollectionHolder"));
                    HollowObjectWriteRecord countryRec = new HollowObjectWriteRecord((HollowObjectSchema) output.getSchema("ISOCountry"));
                    countryRec.setString("id", country.getId());

                    HollowObjectWriteRecord videoRec = new HollowObjectWriteRecord((HollowObjectSchema)output.getSchema("Video"));
                    HollowObjectWriteRecord personRec = new HollowObjectWriteRecord((HollowObjectSchema)output.getSchema("VPerson"));
                    HollowObjectWriteRecord episodeRec = new HollowObjectWriteRecord((HollowObjectSchema)output.getSchema("Episode"));

                    int videoMapOrdinal = writeToOutput(videoLists, videoRec, "Video", "value", output);
                    int episodeMapOrdinal = writeToOutput(episodeLists, episodeRec, "Episode", "id", output);
                    int personMapOrdinal = writeToOutput(personLists, personRec, "VPerson", "id", output);
                    int countryOrdinal = output.add("ISOCountry", countryRec);

                    holderRec.setReference("videoListMap", videoMapOrdinal);
                    holderRec.setReference("episodeListMap", episodeMapOrdinal);
                    holderRec.setReference("personListMap", personMapOrdinal);
                    holderRec.setReference("resourceIdListMap", emptyResourceIdMapOrdinal);
                    holderRec.setReference("country", countryOrdinal);

                    output.add("NamedCollectionHolder", holderRec);
                }
            });
        }

        executor.awaitSuccessfulCompletion();
    }

    // @TODO: Needs to be removed once client code is cleaned up
    private int writeEmptyResourceIdMapToOutput(HollowWriteStateEngine output) {
        return output.add("MapOfStringsToSetOfNFResourceID", new HollowMapWriteRecord());
    }


    private int writeToOutput(Map<String, Set<Integer>> itemLists, HollowObjectWriteRecord itemRec, String typeName, String itemIdFieldName, HollowWriteStateEngine output) {
        String setName = "SetOf" + typeName;
        HollowMapWriteRecord mapRec = new HollowMapWriteRecord();
        HollowSetWriteRecord setRec = new HollowSetWriteRecord();
        HollowObjectWriteRecord stringsRec = new HollowObjectWriteRecord((HollowObjectSchema)output.getSchema("Strings"));

        for(Map.Entry<String, Set<Integer>> itemListEntry : itemLists.entrySet()) {
            stringsRec.setString("value", itemListEntry.getKey());
            int nameOrdinal = output.add("Strings", stringsRec);
            setRec.reset();
            for(Integer itemId : itemListEntry.getValue()) {
                itemRec.setInt(itemIdFieldName, itemId.intValue());
                int itemOrdinal = output.add(typeName, itemRec);
                setRec.addElement(itemOrdinal, itemId.intValue());
            }

            int setOrdinal = output.add(setName, setRec);
            mapRec.addEntry(nameOrdinal, setOrdinal, itemListEntry.getKey().hashCode());
        }

        return output.add("MapOfStringsToSetOf" + typeName, mapRec);
    }


}
