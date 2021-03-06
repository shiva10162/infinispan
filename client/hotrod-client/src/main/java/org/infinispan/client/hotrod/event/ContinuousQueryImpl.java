package org.infinispan.client.hotrod.event;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryCreated;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryExpired;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryModified;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryRemoved;
import org.infinispan.client.hotrod.annotation.ClientListener;
import org.infinispan.client.hotrod.filter.Filters;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.api.continuous.ContinuousQuery;
import org.infinispan.query.api.continuous.ContinuousQueryListener;
import org.infinispan.query.remote.client.ContinuousQueryResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A container of continuous query listeners for a cache.
 * <p>This class is not threadsafe.
 *
 * @author anistor@redhat.com
 * @since 8.2
 * @private
 */
public final class ContinuousQueryImpl<K, V> implements ContinuousQuery<K, V> {

   private final RemoteCache<K, V> cache;

   private final SerializationContext serializationContext;

   private final List<ClientEntryListener<K, ?>> listeners = new ArrayList<>();

   public ContinuousQueryImpl(RemoteCache<K, V> cache) {
      if (cache == null) {
         throw new IllegalArgumentException("cache parameter cannot be null");
      }
      this.cache = cache;
      serializationContext = ProtoStreamMarshaller.getSerializationContext(cache.getRemoteCacheManager());
   }

   /**
    * Registers a continuous query listener that uses a query DSL based filter. The listener will receive notifications
    * when a cache entry joins or leaves the matching set defined by the query.
    *
    * @param listener the continuous query listener instance
    * @param query    the query to be used for determining the matching set
    */
   public <C> void addContinuousQueryListener(Query query, ContinuousQueryListener<K, C> listener) {
      ClientEntryListener<K, ?> eventListener = new ClientEntryListener<>(serializationContext, listener);
      Object[] factoryParams = Filters.makeFactoryParams(query);
      cache.addClientListener(eventListener, factoryParams, null);
      listeners.add(eventListener);
   }

   public void removeContinuousQueryListener(ContinuousQueryListener<K, ?> listener) {
      for (Iterator<ClientEntryListener<K, ?>> it = listeners.iterator(); it.hasNext(); ) {
         ClientEntryListener l = it.next();
         if (l.listener == listener) {
            cache.removeClientListener(l);
            it.remove();
            break;
         }
      }
   }

   public List<ContinuousQueryListener<K, ?>> getListeners() {
      List<ContinuousQueryListener<K, ?>> queryListeners = new ArrayList<>(listeners.size());
      for (ClientEntryListener<K, ?> l : listeners) {
         queryListeners.add(l.listener);
      }
      return queryListeners;
   }

   public void removeAllListeners() {
      for (ClientEntryListener l : listeners) {
         cache.removeClientListener(l);
      }
      listeners.clear();
   }

   @ClientListener(filterFactoryName = Filters.CONTINUOUS_QUERY_FILTER_FACTORY_NAME,
         converterFactoryName = Filters.CONTINUOUS_QUERY_FILTER_FACTORY_NAME,
         useRawData = true, includeCurrentState = true)
   private static final class ClientEntryListener<K, C> {

      private final SerializationContext serializationContext;

      private final ContinuousQueryListener<K, C> listener;

      ClientEntryListener(SerializationContext serializationContext, ContinuousQueryListener<K, C> listener) {
         this.serializationContext = serializationContext;
         this.listener = listener;
      }

      @ClientCacheEntryCreated
      @ClientCacheEntryModified
      @ClientCacheEntryRemoved
      @ClientCacheEntryExpired
      public void handleEvent(ClientCacheEntryCustomEvent<byte[]> event) throws IOException {
         byte[] eventData = event.getEventData();
         ContinuousQueryResult cqr = (ContinuousQueryResult) ProtobufUtil.fromWrappedByteArray(serializationContext, eventData);
         Object key = ProtobufUtil.fromWrappedByteArray(serializationContext, cqr.getKey());
         Object value = cqr.getValue() != null ? ProtobufUtil.fromWrappedByteArray(serializationContext, cqr.getValue()) : cqr.getProjection();
         if (cqr.isJoining()) {
            listener.resultJoining((K) key, (C) value);
         } else {
            listener.resultLeaving((K) key);
         }
      }
   }
}
