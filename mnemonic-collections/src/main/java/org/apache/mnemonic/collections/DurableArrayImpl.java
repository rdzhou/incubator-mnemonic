/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mnemonic.collections;

import org.apache.mnemonic.EntityFactoryProxy;
import org.apache.mnemonic.GenericField;
import org.apache.mnemonic.DurableType;
import org.apache.mnemonic.MemChunkHolder;
import org.apache.mnemonic.MemoryDurableEntity;
import org.apache.mnemonic.OutOfHybridMemory;
import org.apache.mnemonic.RestorableAllocator;
import org.apache.mnemonic.RestoreDurableEntityError;
import org.apache.mnemonic.RetrieveDurableEntityError;
import org.apache.mnemonic.Utils;

import sun.misc.Unsafe;
import java.util.Iterator;

@SuppressWarnings("restriction")
public class DurableArrayImpl<A extends RestorableAllocator<A>, E>
        extends DurableArray<E> implements MemoryDurableEntity<A> {

  private static final int MAX_OBJECT_SIZE = 8;
  private static long[][] fieldInfo;
  private Object[] genericField;
  private Unsafe unsafe;
  private EntityFactoryProxy[] factoryProxy;
  private DurableType[] genericType;
  private volatile boolean autoReclaim;
  private MemChunkHolder<A> holder;
  private A allocator;

  public DurableArrayImpl() {
    super(0);
  }

  public DurableArrayImpl(int size) {
    super(size);
  }

  /**
   * get item from the given index of array
   *
   * @return item from the given index of array
   */
  public E get(int index) {
    if (index >= arraySize) {
        throw new RetrieveDurableEntityError("Index greater than array size.");
    }
    if (null == genericField[index]) {
      EntityFactoryProxy proxy = null;
      DurableType gftype = null;
      if (null != factoryProxy) {
        proxy = factoryProxy[0];
      }
      if (null != genericType) {
        gftype = genericType[0];
      } else {
        throw new RetrieveDurableEntityError("No Generic Field Type Info.");
      }
      genericField[index] = new GenericField<A, E>(proxy, gftype, factoryProxy, genericType, allocator,
                                  unsafe, autoReclaim, holder.get() + index * MAX_OBJECT_SIZE);
    }
    if (null != genericField[index]) {
      return ((GenericField<A, E>)genericField[index]).get();
    } else {
      throw new RetrieveDurableEntityError("GenericField is null!");
    }
  }

  /**
   * set a value at a given index
   *
   * @param value
   *          the value to be set
   */
  public void set(int index, E value) {
    set(index, value, true);
  }

  /**
   * set a value at a given index
   *
   * @param value
   *          the value to be set
   *
   * @param destroy
   *          true if want to destroy exist one
   *
   */
  public void set(int index, E value, boolean destroy) {
    if (index >= arraySize) {
        throw new RetrieveDurableEntityError("Index greater than array size.");
    }
    if (null == genericField[index]) {
      EntityFactoryProxy proxy = null;
      DurableType gftype = null;
      if (null != factoryProxy) {
        proxy = factoryProxy[0];
      }
      if (null != genericType) {
        gftype = genericType[0];
      } else {
        throw new RetrieveDurableEntityError("No Generic Field Type Info.");
      }
      genericField[index] = new GenericField<A, E>(proxy, gftype, factoryProxy, genericType, allocator,
                                  unsafe, autoReclaim, holder.get() + index * MAX_OBJECT_SIZE);
    }
    if (null != genericField[index]) {
      ((GenericField<A, E>)genericField[index]).set(value, destroy);
    } else {
      throw new RetrieveDurableEntityError("GenericField is null!");
    }
  }

  @Override
  public boolean autoReclaim() {
    return autoReclaim;
  }

  @Override
  public long[][] getNativeFieldInfo() {
    return fieldInfo;
  }

  @Override
  public void destroy() throws RetrieveDurableEntityError {
    long startAddr = holder.get();
    long endAddr = startAddr + MAX_OBJECT_SIZE * arraySize;
    int index = 0;
    while (startAddr < endAddr) {
      if (null != get(index)) {
        genericField[index] = null;
      }
      index++;
      startAddr += MAX_OBJECT_SIZE;
    }
    holder.destroy();
  }

  @Override
  public void cancelAutoReclaim() {
    holder.cancelAutoReclaim();
    autoReclaim = false;
  }

  @Override
  public void registerAutoReclaim() {
    holder.registerAutoReclaim();
    autoReclaim = true;
  }

  @Override
  public long getHandler() {
    return allocator.getChunkHandler(holder);
  }

  @Override
  public void restoreDurableEntity(A allocator, EntityFactoryProxy[] factoryProxy,
             DurableType[] gType, long phandler, boolean autoReclaim) throws RestoreDurableEntityError {
    initializeDurableEntity(allocator, factoryProxy, gType, autoReclaim);
    if (0L == phandler) {
      throw new RestoreDurableEntityError("Input handler is null on restoreDurableEntity.");
    }
    holder = allocator.retrieveChunk(phandler, autoReclaim);
    if (null == holder) {
      throw new RestoreDurableEntityError("Retrieve Entity Failure!");
    }
    arraySize = ((int)(holder.getSize() / MAX_OBJECT_SIZE));
    genericField = new Object[arraySize];
    initializeAfterRestore();
  }


  @Override
  public void initializeDurableEntity(A allocator, EntityFactoryProxy[] factoryProxy,
              DurableType[] gType, boolean autoReclaim) {
    this.allocator = allocator;
    this.factoryProxy = factoryProxy;
    this.genericType = gType;
    this.autoReclaim = autoReclaim;
    try {
      this.unsafe = Utils.getUnsafe();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void createDurableEntity(A allocator, EntityFactoryProxy[] factoryProxy,
              DurableType[] gType, boolean autoReclaim) throws OutOfHybridMemory {
    initializeDurableEntity(allocator, factoryProxy, gType, autoReclaim);
    this.holder = allocator.createChunk(MAX_OBJECT_SIZE * arraySize, autoReclaim);
    if (null == this.holder) {
      throw new OutOfHybridMemory("Create Durable Entity Error!");
    }
    genericField = new Object[arraySize];
    initializeAfterCreate();
  }

  @Override
  public Iterator<E> iterator() {
    return null;
  }
}
