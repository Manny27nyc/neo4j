/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime

import org.neo4j.cypher.internal.runtime.ResourceManager.INITIAL_CAPACITY
import org.neo4j.cypher.internal.runtime.SingleThreadedResourcePool.SHALLOW_SIZE
import org.neo4j.internal.helpers.Exceptions
import org.neo4j.internal.kernel.api.AutoCloseablePlus
import org.neo4j.internal.kernel.api.CloseListener
import org.neo4j.memory.EmptyMemoryTracker
import org.neo4j.memory.HeapEstimator.shallowSizeOfInstance
import org.neo4j.memory.HeapEstimator.shallowSizeOfObjectArray
import org.neo4j.memory.MemoryTracker

import scala.collection.JavaConverters.asScalaIteratorConverter

class ResourceManager(monitor: ResourceMonitor = ResourceMonitor.NOOP, memoryTracker: MemoryTracker = EmptyMemoryTracker.INSTANCE) extends CloseableResource with CloseListener {
  protected val resources: ResourcePool = new SingleThreadedResourcePool(INITIAL_CAPACITY, monitor, memoryTracker)

  /**
   * Trace a resource
   */
  def trace(resource: AutoCloseablePlus): Unit = {
    monitor.trace(resource)
    resources.add(resource)
    resource.setCloseListener(this)
  }

  /**
   * Stop tracing a resource, don't close it.
   */
  def untrace(resource: AutoCloseablePlus): Unit = {
    monitor.untrace(resource)
    resources.remove(resource)
    resource.setCloseListener(null)
  }

  /**
   * Called when the resource is closed.
   */
  override def onClosed(resource: AutoCloseablePlus): Unit = {
    monitor.close(resource)
    // close is idempotent and can be called multiple times, but we want to get here only once.
    resource.setCloseListener(null)
    if (!resources.remove(resource)) {

      throw new IllegalStateException(s"$resource is not in the resource set $resources")
    }
  }

  def allResources: Iterator[AutoCloseablePlus] = resources.all()

  override def close(): Unit = resources.closeAll()
}

class ThreadSafeResourceManager(monitor: ResourceMonitor) extends ResourceManager(monitor) {
  override protected val resources:ResourcePool = new ThreadSafeResourcePool(monitor)
}

object ResourceManager {
  val INITIAL_CAPACITY: Int = 8
}

trait ResourceMonitor {
  def trace(resource: AutoCloseablePlus): Unit
  def untrace(resource: AutoCloseablePlus): Unit
  def close(resource: AutoCloseablePlus): Unit
}

object ResourceMonitor {
  val NOOP: ResourceMonitor = new ResourceMonitor {
    def trace(resource: AutoCloseablePlus): Unit = {}
    def untrace(resource: AutoCloseablePlus): Unit = {}
    def close(resource: AutoCloseablePlus): Unit = {}
  }
}

/**
 * Used by LoadCsvPeriodicCommitObserver to close all cursors in a cursor pool
 */
trait ResourceManagedCursorPool {
  def closeCursors(): Unit
}

trait ResourcePool {
  def add(resource: AutoCloseablePlus): Unit
  def remove(resource: AutoCloseablePlus): Boolean
  def all(): Iterator[AutoCloseablePlus]
  def clear(): Unit
  def closeAll(): Unit
  override def toString: String = all().toList.toString()
}

/**
 * Similar to an ArrayList[AutoCloseablePlus] but does faster removes since it simply set the element to null and
 * does not reorder the backing array.
 * @param capacity the initial capacity of the pool
 * @param monitor the monitor to call on close
 */
class SingleThreadedResourcePool(capacity: Int, monitor: ResourceMonitor, memoryTracker: MemoryTracker) extends ResourcePool {
  private var highMark: Int = 0
  private var closeables: Array[AutoCloseablePlus] = new Array[AutoCloseablePlus](capacity)
  private var trackedSize = shallowSizeOfObjectArray(capacity)
  memoryTracker.allocateHeap(SHALLOW_SIZE + trackedSize)

  def add(resource: AutoCloseablePlus): Unit = {
    ensureCapacity()
    closeables(highMark) = resource
    resource.setToken(highMark)
    highMark += 1
  }

  def remove(resource: AutoCloseablePlus): Boolean = {
    val i = resource.getToken
    if (i < highMark) {
      if (!(closeables(i) eq resource)) {
        throw new IllegalStateException(s"$resource does not match ${closeables(i)}")
      }
      closeables(i) = null
      if (i == highMark - 1) { //we removed the last item, hence no holes
        highMark -= 1
      }
      true
    } else {
      false
    }
  }

  def all(): Iterator[AutoCloseablePlus] = new Iterator[AutoCloseablePlus] {
    private var offset = 0

    override def hasNext: Boolean = {
      while (offset < highMark && closeables(offset) == null) {
        offset += 1
      }

      offset < highMark
    }

    override def next(): AutoCloseablePlus = {
      if (!hasNext) {
        throw new IndexOutOfBoundsException
      }
      val closeable = closeables(offset)
      offset += 1
      closeable
    }
  }

  def clear(): Unit = {
    highMark = 0
    if (closeables != null) {
      memoryTracker.releaseHeap(trackedSize + SHALLOW_SIZE)
    }
  }

  def closeAll(): Unit = {
    var error: Throwable = null
    var i = 0
    while (i < highMark) {
      try {
        val resource = closeables(i)
        if (resource != null) {
          monitor.close(resource)
          resource.setCloseListener(null) // We don't want a call to onClosed any longer
          resource.close()
        }
      }
      catch {
        case t: Throwable => error = Exceptions.chain(error, t)
      }
      i += 1
    }
    if (error != null) throw error
    else {
      clear()
    }
  }

  private def ensureCapacity(): Unit = {
    if (closeables.length <= highMark) {
      val temp = closeables
      val oldHeapUsage = trackedSize
      val newSize = closeables.length * 2
      trackedSize = shallowSizeOfObjectArray(newSize)
      memoryTracker.allocateHeap(trackedSize)
      closeables = new Array[AutoCloseablePlus](newSize)
      System.arraycopy(temp, 0, closeables, 0, temp.length)
      memoryTracker.releaseHeap(oldHeapUsage)
    }
  }
}

object SingleThreadedResourcePool {
  private val SHALLOW_SIZE = shallowSizeOfInstance(classOf[SingleThreadedResourcePool])
}

class ThreadSafeResourcePool(monitor: ResourceMonitor) extends ResourcePool {

  val resources: java.util.Collection[AutoCloseablePlus] = new java.util.concurrent.ConcurrentLinkedQueue[AutoCloseablePlus]()

  override def add(resource: AutoCloseablePlus): Unit =
    resources.add(resource)

  override def remove(resource: AutoCloseablePlus): Boolean = resources.remove(resource)

  override def all(): Iterator[AutoCloseablePlus] = resources.iterator().asScala

  override def clear(): Unit = resources.clear()

  override def closeAll(): Unit = {
    val iterator = resources.iterator()
    var error: Throwable = null
    while (iterator.hasNext) {
      try {
        val resource = iterator.next()
        monitor.close(resource)
        resource.setCloseListener(null) // We don't want a call to onClosed any longer
        resource.close()
      }
      catch {
        case t: Throwable => error = Exceptions.chain(error, t)
      }
    }
    if (error != null) throw error
    else {
      resources.clear()
    }
  }
}
