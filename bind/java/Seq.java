// Copyright 2014 The Go Authors. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package go;

import android.content.Context;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.logging.Logger;

import go.Universe;

// Seq is a sequence of machine-dependent encoded values.
// Used by automatically generated language bindings to talk to Go.
public class Seq {
	private static Logger log = Logger.getLogger("GoSeq");

	// also known to bind/seq/ref.go and bind/objc/seq_darwin.m
	private static final int NULL_REFNUM = 41;

	// use single Ref for null Object
	public static final Ref nullRef = new Ref(NULL_REFNUM, null);

	static {
		System.out.println("proxy_service: before native init");
		init();
		System.out.println("proxy_service: after native init");
		Universe.touch();
	}

	// setContext sets the context in the go-library to be used in RunOnJvm.
	public static void setContext(Context context) {
		setContext((java.lang.Object)context);
	}

	private static native void init();

	// Empty method to run class initializer
	public static void touch() {}

	private Seq() {
	}

	// ctx is an android.context.Context.
	static native void setContext(java.lang.Object ctx);

	public static void incRefnum(int refnum) {
		tracker.incRefnum(refnum);
	}

	// incRef increments the reference count of Java objects.
	// For proxies for Go objects, it calls into the Proxy method
	// incRefnum() to make sure the Go reference count is positive
	// even if the Proxy is garbage collected and its Ref is finalized.
	public static int incRef(Object o) {
		return tracker.inc(o);
	}

	public static int incGoObjectRef(GoObject o) {
		return o.incRefnum();
	}

	public static Ref getRef(int refnum) {
		return tracker.get(refnum);
	}

	// Increment the Go reference count before sending over a refnum.
	public static native void incGoRef(int refnum);

	// Informs the Go ref tracker that Java is done with this ref.
	static native void destroyRef(int refnum);

	// decRef is called from seq.FinalizeRef
	static void decRef(int refnum) {
		tracker.dec(refnum);
	}

	// A GoObject is a Java class implemented in Go. When a GoObject
	// is passed to Go, it is wrapped in a Go proxy, to make it behave
	// the same as passing a regular Java class.
	public interface GoObject {
		// Increment refcount and return the refnum of the proxy.
		//
		// The Go reference count need to be bumped while the
		// refnum is passed to Go, to avoid finalizing and
		// invalidating it before being translated on the Go side.
		int incRefnum();
	}
	// A Proxy is a Java object that proxies a Go object. Proxies, unlike
	// GoObjects, are unwrapped to their Go counterpart when deserialized
	// in Go.
	public interface Proxy extends GoObject {}

	// A Ref is an object tagged with an integer for passing back and
	// forth across the language boundary.
	//
	// A Ref may represent either an instance of a Java object,
	// or an instance of a Go object. The explicit allocation of a Ref
	// is used to pin Go object instances when they are passed to Java.
	// The Go Seq library maintains a reference to the instance in a map
	// keyed by the Ref number. When the JVM calls finalize, we ask Go
	// to clear the entry in the map.
	public static final class Ref {
		// refnum < 0: Go object tracked by Java
		// refnum > 0: Java object tracked by Go
		public final int refnum;

		private int refcnt;  // for Java obj: track how many times sent to Go.

		public final Object obj;  // for Java obj: pointers to the Java obj.

		Ref(int refnum, Object o) {
			this.refnum = refnum;
			this.refcnt = 0;
			this.obj = o;
		}

		@Override
		protected void finalize() throws Throwable {
			if (refnum < 0) {
				// Go object: signal Go to decrement the reference count.
				Seq.destroyRef(refnum);
			}
			super.finalize();
		}

		void inc() {
			// Count how many times this ref's Java object is passed to Go.
			if (refcnt == Integer.MAX_VALUE) {
				throw new RuntimeException("refnum " + refnum + " overflow");
			}
			refcnt++;
		}
	}

	static final RefTracker tracker = new RefTracker();

	static final class RefTracker {
		private static final int REF_OFFSET = 42;

		// Next Java object reference number.
		//
		// Reference numbers are positive for Java objects,
		// and start, arbitrarily at a different offset to Go
		// to make debugging by reading Seq hex a little easier.
		private int next = REF_OFFSET; // next Java object ref

		// Java objects that have been passed to Go. refnum -> Ref
		// The Ref obj field is non-null.
		// This map pins Java objects so they don't get GCed while the
		// only reference to them is held by Go code.
		private final RefMap javaObjs = new RefMap();

		// Java objects to refnum
		private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

		// inc increments the reference count of a Java object when it
		// is sent to Go. inc returns the refnum for the object.
		synchronized int inc(Object o) {
			if (o == null) {
				return NULL_REFNUM;
			}
			if (o instanceof Proxy) {
				return ((Proxy)o).incRefnum();
			}
			Integer refnumObj = javaRefs.get(o);
			if (refnumObj == null) {
				if (next == Integer.MAX_VALUE) {
					throw new RuntimeException("createRef overflow for " + o);
				}
				refnumObj = next++;
				javaRefs.put(o, refnumObj);
			}
			int refnum = refnumObj;
			Ref ref = javaObjs.get(refnum);
			if (ref == null) {
				ref = new Ref(refnum, o);
				javaObjs.put(refnum, ref);
			}
			ref.inc();
			return refnum;
		}

		synchronized void incRefnum(int refnum) {
			Ref ref = javaObjs.get(refnum);
			if (ref == null) {
				throw new RuntimeException("referenced Java object is not found: refnum="+refnum);
			}
			ref.inc();
		}

		// dec decrements the reference count of a Java object when
		// Go signals a corresponding proxy object is finalized.
		// If the count reaches zero, the Java object is removed
		// from the javaObjs map.
		synchronized void dec(int refnum) {
			if (refnum <= 0) {
				// We don't keep track of the Go object.
				// This must not happen.
				log.severe("dec request for Go object "+ refnum);
				return;
			}
			if (refnum == Seq.nullRef.refnum) {
				return;
			}
			// Java objects are removed on request of Go.
			Ref obj = javaObjs.get(refnum);
			if (obj == null) {
				throw new RuntimeException("referenced Java object is not found: refnum="+refnum);
			}
			obj.refcnt--;
			if (obj.refcnt <= 0) {
				javaObjs.remove(refnum);
				javaRefs.remove(obj.obj);
			}
		}

		// get returns an existing Ref to either a Java or Go object.
		// It may be the first time we have seen the Go object.
		//
		// TODO(crawshaw): We could cut down allocations for frequently
		// sent Go objects by maintaining a map to weak references. This
		// however, would require allocating two objects per reference
		// instead of one. It also introduces weak references, the bane
		// of any Java debugging session.
		//
		// When we have real code, examine the tradeoffs.
		synchronized Ref get(int refnum) {
			if (refnum == NULL_REFNUM) {
				return nullRef;
			} else if (refnum > 0) {
				Ref ref = javaObjs.get(refnum);
				if (ref == null) {
					throw new RuntimeException("unknown java Ref: "+refnum);
				}
				return ref;
			} else {
				// Go object.
				return new Ref(refnum, null);
			}
		}
	}

	// RefMap is a mapping of integers to Ref objects.
	//
	// The integers can be sparse. In Go this would be a map[int]*Ref.
	static final class RefMap {
		private int next = 0;
		private int live = 0;
		private int[] keys = new int[16];
		private Ref[] objs = new Ref[16];

		RefMap() {}

		Ref get(int key) {
			int i = Arrays.binarySearch(keys, 0, next, key);
			if (i >= 0) {
				return objs[i];
			}
			return null;
		}

		void remove(int key) {
			int i = Arrays.binarySearch(keys, 0, next, key);
			if (i >= 0) {
				if (objs[i] != null) {
					objs[i] = null;
					live--;
				}
			}
		}

		void put(int key, Ref obj) {
			if (obj == null) {
				throw new RuntimeException("put a null ref (with key "+key+")");
			}
			int i = Arrays.binarySearch(keys, 0, next, key);
			if (i >= 0) {
				if (objs[i] == null) {
					objs[i] = obj;
					live++;
				}
				if (objs[i] != obj) {
					throw new RuntimeException("replacing an existing ref (with key "+key+")");
				}
				return;
			}
			if (next >= keys.length) {
				grow();
				i = Arrays.binarySearch(keys, 0, next, key);
			}
			i = ~i;
			if (i < next) {
				// Insert, shift everything afterwards down.
				System.arraycopy(keys, i, keys, i+1, next-i);
				System.arraycopy(objs, i, objs, i+1, next-i);
			}
			keys[i] = key;
			objs[i] = obj;
			live++;
			next++;
		}

		private void grow() {
			// Compact and (if necessary) grow backing store.
			int[] newKeys;
			Ref[] newObjs;
			int len = 2*roundPow2(live);
			if (len > keys.length) {
				newKeys = new int[keys.length*2];
				newObjs = new Ref[objs.length*2];
			} else {
				newKeys = keys;
				newObjs = objs;
			}

			int j = 0;
			for (int i = 0; i < keys.length; i++) {
				if (objs[i] != null) {
					newKeys[j] = keys[i];
					newObjs[j] = objs[i];
					j++;
				}
			}
			for (int i = j; i < newKeys.length; i++) {
				newKeys[i] = 0;
				newObjs[i] = null;
			}

			keys = newKeys;
			objs = newObjs;
			next = j;

			if (live != next) {
				throw new RuntimeException("bad state: live="+live+", next="+next);
			}
		}

		private static int roundPow2(int x) {
			int p = 1;
			while (p < x) {
				p *= 2;
			}
			return p;
		}
	}
}
