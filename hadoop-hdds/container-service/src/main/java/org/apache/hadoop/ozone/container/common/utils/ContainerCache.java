/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.common.utils;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.util.concurrent.Striped;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.utils.MetadataStore;
import org.apache.hadoop.hdds.utils.MetadataStoreBuilder;
import org.apache.hadoop.ozone.OzoneConfigKeys;

import com.google.common.base.Preconditions;
import org.apache.commons.collections.MapIterator;
import org.apache.commons.collections.map.LRUMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * container cache is a LRUMap that maintains the DB handles.
 */
public final class ContainerCache extends LRUMap {
  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerCache.class);
  private final Lock lock = new ReentrantLock();
  private static ContainerCache cache;
  private static final float LOAD_FACTOR = 0.75f;
  private final Striped<Lock> rocksDBLock;
  /**
   * Constructs a cache that holds DBHandle references.
   */
  private ContainerCache(int maxSize, int stripes, float loadFactor, boolean
      scanUntilRemovable) {
    super(maxSize, loadFactor, scanUntilRemovable);
    rocksDBLock = Striped.lazyWeakLock(stripes);
  }

  /**
   * Return a singleton instance of {@link ContainerCache}
   * that holds the DB handlers.
   *
   * @param conf - Configuration.
   * @return A instance of {@link ContainerCache}.
   */
  public synchronized static ContainerCache getInstance(
      ConfigurationSource conf) {
    if (cache == null) {
      int cacheSize = conf.getInt(OzoneConfigKeys.OZONE_CONTAINER_CACHE_SIZE,
          OzoneConfigKeys.OZONE_CONTAINER_CACHE_DEFAULT);
      int stripes = conf.getInt(
          OzoneConfigKeys.OZONE_CONTAINER_CACHE_LOCK_STRIPES,
          OzoneConfigKeys.OZONE_CONTAINER_CACHE_LOCK_STRIPES_DEFAULT);
      cache = new ContainerCache(cacheSize, stripes, LOAD_FACTOR, true);
    }
    return cache;
  }

  /**
   * Closes all the db instances and resets the cache.
   */
  public void shutdownCache() {
    lock.lock();
    try {
      // iterate the cache and close each db
      MapIterator iterator = cache.mapIterator();
      while (iterator.hasNext()) {
        iterator.next();
        ReferenceCountedDB db = (ReferenceCountedDB) iterator.getValue();
        Preconditions.checkArgument(db.cleanup(), "refCount:",
            db.getReferenceCount());
      }
      // reset the cache
      cache.clear();
    } finally {
      lock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean removeLRU(LinkEntry entry) {
    ReferenceCountedDB db = (ReferenceCountedDB) entry.getValue();
    lock.lock();
    try {
      return db.cleanup();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns a DB handle if available, create the handler otherwise.
   *
   * @param containerID - ID of the container.
   * @param containerDBType - DB type of the container.
   * @param containerDBPath - DB path of the container.
   * @param conf - Hadoop Configuration.
   * @return ReferenceCountedDB.
   */
  public ReferenceCountedDB getDB(long containerID, String containerDBType,
                             String containerDBPath, ConfigurationSource conf)
      throws IOException {
    Preconditions.checkState(containerID >= 0,
        "Container ID cannot be negative.");
    ReferenceCountedDB db;
    Lock containerLock = rocksDBLock.get(containerDBPath);
    containerLock.lock();
    try {
      lock.lock();
      try {
        db = (ReferenceCountedDB) this.get(containerDBPath);
        if (db != null) {
          db.incrementReference();
          return db;
        }
      } finally {
        lock.unlock();
      }

      try {
        MetadataStore metadataStore =
            MetadataStoreBuilder.newBuilder()
                .setDbFile(new File(containerDBPath))
                .setCreateIfMissing(false)
                .setConf(conf)
                .setDBType(containerDBType)
                .build();
        db = new ReferenceCountedDB(metadataStore, containerDBPath);
      } catch (Exception e) {
        LOG.error("Error opening DB. Container:{} ContainerPath:{}",
            containerID, containerDBPath, e);
        throw e;
      }

      lock.lock();
      try {
        ReferenceCountedDB currentDB =
            (ReferenceCountedDB) this.get(containerDBPath);
        if (currentDB != null) {
          // increment the reference before returning the object
          currentDB.incrementReference();
          // clean the db created in previous step
          db.cleanup();
          return currentDB;
        } else {
          this.put(containerDBPath, db);
          // increment the reference before returning the object
          db.incrementReference();
          return db;
        }
      } finally {
        lock.unlock();
      }
    } finally {
      containerLock.unlock();
    }
  }

  /**
   * Remove a DB handler from cache.
   *
   * @param containerDBPath - path of the container db file.
   */
  public void removeDB(String containerDBPath) {
    lock.lock();
    try {
      ReferenceCountedDB db = (ReferenceCountedDB)this.get(containerDBPath);
      if (db != null) {
        Preconditions.checkArgument(db.cleanup(), "refCount:",
            db.getReferenceCount());
      }
      this.remove(containerDBPath);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Add a DB handler into cache.
   *
   * @param containerDBPath - DB path of the container.
   * @param db - DB handler
   */
  public void addDB(String containerDBPath, ReferenceCountedDB db) {
    lock.lock();
    try {
      this.putIfAbsent(containerDBPath, db);
    } finally {
      lock.unlock();
    }
  }
}
