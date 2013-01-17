/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.collection;

import com.hazelcast.collection.list.ObjectListProxy;
import com.hazelcast.collection.multimap.ObjectMultiMapProxy;
import com.hazelcast.core.*;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.spi.*;

import java.util.EventListener;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @ali 1/1/13
 */
public class CollectionService implements ManagedService, RemoteService, EventPublishingService<CollectionEvent, EventListener> {

    private NodeEngine nodeEngine;

    public static final String COLLECTION_SERVICE_NAME = "hz:impl:collectionService";

    private final ConcurrentMap<ListenerKey, String> eventRegistrations = new ConcurrentHashMap<ListenerKey, String>();

    private final CollectionPartitionContainer[] partitionContainers;

    public CollectionService(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        partitionContainers = new CollectionPartitionContainer[nodeEngine.getPartitionCount()];
    }

    public CollectionContainer getOrCreateCollectionContainer(int partitionId, CollectionProxyId proxyId) {
        return partitionContainers[partitionId].getOrCreateCollectionContainer(proxyId);
    }

    public CollectionPartitionContainer getPartitionContainer(int partitionId) {
        return partitionContainers[partitionId];
    }

    public void init(NodeEngine nodeEngine, Properties properties) {
        this.nodeEngine = nodeEngine;
        int partitionCount = nodeEngine.getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            partitionContainers[i] = new CollectionPartitionContainer(this);
        }
    }

    public void destroy() {
    }

    Object createNew(CollectionProxyId proxyId) {
        CollectionProxy proxy = (CollectionProxy) nodeEngine.getProxyService().getDistributedObject(COLLECTION_SERVICE_NAME, proxyId);
        return proxy.createNew();
    }

    public String getServiceName() {
        return COLLECTION_SERVICE_NAME;
    }

    public DistributedObject createDistributedObject(Object objectId) {
        CollectionProxyId collectionProxyId = (CollectionProxyId) objectId;
        final String name = collectionProxyId.name;
        final CollectionProxyType type = collectionProxyId.type;
        switch (type) {
            case MULTI_MAP:
                return new ObjectMultiMapProxy(name, this, nodeEngine, collectionProxyId.type);
            case LIST:
                return new ObjectListProxy(name, this, nodeEngine, collectionProxyId.type);
            case SET:
                return null;
            case QUEUE:
                return null;
        }
        throw new IllegalArgumentException();
    }

    public DistributedObject createDistributedObjectForClient(Object objectId) {
        return createDistributedObject(objectId);
    }

    public void destroyDistributedObject(Object objectId) {

    }

    public Set<Data> localKeySet(CollectionProxyId proxyId) {
        Set<Data> keySet = new HashSet<Data>();
        for (CollectionPartitionContainer partitionContainer : partitionContainers) {
            CollectionContainer container = partitionContainer.getOrCreateCollectionContainer(proxyId);
            keySet.addAll(container.keySet());
        }
        return keySet;
    }

    public SerializationService getSerializationService() {
        return nodeEngine.getSerializationService();
    }

    public NodeEngine getNodeEngine() {
        return nodeEngine;
    }

    public void addListener(String name, EventListener listener, Data key, boolean includeValue, boolean local) {
        ListenerKey listenerKey = new ListenerKey(name, key, listener);
        String id = eventRegistrations.putIfAbsent(listenerKey, "tempId");
        if (id != null) {
            return;
        }
        EventService eventService = nodeEngine.getEventService();
        EventRegistration registration = null;
        if (local) {
            registration = eventService.registerLocalListener(COLLECTION_SERVICE_NAME, name, new CollectionEventFilter(includeValue, key), listener);
        } else {
            registration = eventService.registerListener(COLLECTION_SERVICE_NAME, name, new CollectionEventFilter(includeValue, key), listener);
        }

        eventRegistrations.put(listenerKey, registration.getId());
    }

    public void removeListener(String name, EventListener listener, Data key) {
        ListenerKey listenerKey = new ListenerKey(name, key, listener);
        String id = eventRegistrations.remove(listenerKey);
        if (id != null) {
            EventService eventService = nodeEngine.getEventService();
            eventService.deregisterListener(COLLECTION_SERVICE_NAME, name, id);
        }
    }

    public void dispatchEvent(CollectionEvent event, EventListener listener) {
        if (listener instanceof EntryListener) {
            EntryListener entryListener = (EntryListener) listener;
            EntryEvent entryEvent = new EntryEvent(event.getName(), nodeEngine.getCluster().getMember(event.getCaller()),
                    event.getEventType().getType(), nodeEngine.toObject(event.getKey()), nodeEngine.toObject(event.getValue()));


            if (event.eventType.equals(EntryEventType.ADDED)) {
                entryListener.entryAdded(entryEvent);
            } else if (event.eventType.equals(EntryEventType.REMOVED)) {
                entryListener.entryRemoved(entryEvent);
            }
        }
        else if (listener instanceof ItemListener){
            ItemListener itemListener = (ItemListener)listener;
            ItemEvent itemEvent = new ItemEvent(event.getName(), event.eventType.getType(), nodeEngine.toObject(event.getValue()),
                    nodeEngine.getCluster().getMember(event.getCaller()));
            if (event.eventType.getType() == ItemEventType.ADDED.getType()){
                itemListener.itemAdded(itemEvent);
            }
            else {
                itemListener.itemRemoved(itemEvent);
            }
        }
    }
}