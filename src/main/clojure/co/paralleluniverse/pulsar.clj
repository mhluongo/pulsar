;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar
  "Pulsar is an implementation of lightweight threads (fibers),
  Go-like channles and Erlang-like actors for the JVM"
  (:import [java.util.concurrent TimeUnit]
           [jsr166e ForkJoinPool ForkJoinTask]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands SuspendableCallable]
           [co.paralleluniverse.fibers Fiber Joinable FiberInterruptedException]
           [co.paralleluniverse.fibers.instrument]
           [co.paralleluniverse.strands.channels Channel ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel]
           [co.paralleluniverse.actors Actor]
           [co.paralleluniverse.pulsar PulsarActor ClojureHelper])
  (:use [clojure.core.match :only [match]]))


;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

(defn- ops-args
  [pds xs]
  "Used to simplify optional parameters in functions.
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)]
      (if (p? x)
        (cons x (ops-args (rest pds) (rest xs)))
        (cons d (ops-args (rest pds) xs))))
    (seq xs)))

(defn- kps-args
  [args]
  (let [aps (partition-all 2 args)
        [opts-and-vals ps] (split-with #(keyword? (first %)) aps)
        options (into {} (map vec opts-and-vals))
        positionals (reduce into [] ps)]
    [options positionals]))

(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (sequential? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

(defn as-timeunit
  "Converts a keyword to a java.util.concurrent.TimeUnit
  <pre>
  :nanoseconds | :nanos         -> TimeUnit.NANOSECONDS
  :microseconds | :us           -> TimeUnit.MICROSECONDS
  :milliseconds | :millis | :ms -> TimeUnit.MILLISECONDS
  :seconds | :sec               -> TimeUnit.SECONDS
  :minutes | :mins              -> TimeUnit.MINUTES
  :hours | :hrs                 -> TimeUnit.HOURS
  :days                         -> TimeUnit.DAYS
  </pre>
  "
  [x]
  (if (keyword? x)
    (ClojureHelper/keywordToUnit x)
    x))

;; ## fork/join pool

(defn- in-fj-pool?
  "Returns true if we're running inside a fork/join pool; false otherwise."
  []
  (ForkJoinTask/inForkJoinPool))

(defn- current-fj-pool
  "Returns the fork/join pool we're running in; nil if we're not in a fork/join pool."
  []
  (ForkJoinTask/getPool))

(defn make-fj-pool
  "Creates a new ForkJoinPool with the given parallelism and with the given async mode"
  [^Integer parallelism ^Boolean async]
  (ForkJoinPool. parallelism jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil async))

(def fj-pool
  "A global fork/join pool. The pool uses all available processors and runs in the async mode."
  (make-fj-pool (.availableProcessors (Runtime/getRuntime)) true))

;; ***Make agents use the global fork-join pool***

(set-agent-send-executor! fj-pool)
(set-agent-send-off-executor! fj-pool)

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(defn suspendable?
  "Returns true of a function has been instrumented as suspendable; false otherwise."
  [f]
  (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented))

(def suspendable!
  "Makes a function suspendable"
  (sequentialize
   (fn [f] 
     (ClojureHelper/retransform f))))

(defn ^SuspendableCallable asSuspendableCallable
  "wrap a clojure function as a SuspendableCallable"
  {:no-doc true}
  [f]
  (ClojureHelper/asSuspendableCallable f))


(defmacro susfn
  "Creates a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(suspendable! (fn ~@expr)))

(defmacro defsusfn
  "Defines a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(do (defn ~@expr)
     (suspendable! ~(first expr))))

;; ## Fibers

(defn ^ForkJoinPool get-pool 
  {:no-doc true}
  [^ForkJoinPool pool]
  (or pool (current-fj-pool) fj-pool))

(defn ^Fiber fiber1
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  {:no-doc true}
  [^String name ^ForkJoinPool pool ^Integer stacksize ^SuspendableCallable target]
  (Fiber. name (get-pool pool) (int stacksize) target))

(defn ^Fiber fiber
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  [& args]
  (let [[^String name ^ForkJoinPool pool ^Integer stacksize f] (ops-args [[string? nil] [#(instance? ForkJoinPool %) fj-pool] [integer? -1]] args)]
    (Fiber. name (get-pool pool) (int stacksize) (asSuspendableCallable f))))

(defn start
  "Starts a fiber"
  [^Fiber fiber]
  (.start fiber))

(defmacro spawn-fiber
  "Creates and starts a new fiber"
  [& args]
  (let [[{:keys [^String name ^Integer stack-size ^ForkJoinPool pool], :or {stack-size -1}} body] (kps-args args)]
    `(let [f# (suspendable! (fn [] ~@body))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) (asSuspendableCallable f#))]
       (.start fiber#))))

(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))

(defn join 
  ([^Joinable s]
   (.get s))
  ([^Joinable s timeout ^TimeUnit unit]
   (.get s timeout unit)))

;; ## Strands
;; A strand is either a thread or a fiber.

(defn ^Strand current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))


;; ## Channels

(defn attach!
  "Sets a channel's owning strand (fiber or thread).
  This is done automatically the first time a rcv (or one of the primitive-type receive-xxx) is called on the channel."
  [^Channel channel strand]
  (.setStrand channel strand))

(defn channel
  "Creates a channel"
  ([size] (ObjectChannel/create size))
  ([] (ObjectChannel/create -1)))

(defn snd
  "Sends a message to a channel"
  [^Channel channel message]
  (co.paralleluniverse.pulsar.ChannelsHelper/send channel message))

(defn rcv
  "Receives a message from a channel"
  ([^Channel channel]
   (co.paralleluniverse.pulsar.ChannelsHelper/receive channel))
  ([channel timeout unit]
   (co.paralleluniverse.pulsar.ChannelsHelper/receive channel (long timeout) unit)))

;; ### Primitive channels

(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size] (IntChannel/create size))
  ([] (IntChannel/create -1)))

(defmacro send-int
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendInt ~channel (int ~message)))

(defmacro receive-int
  ([channel]
   `(int (co.paralleluniverse.pulsar.ChannelsHelper/receiveInt ~channel)))
  ([channel timeout unit]
   `(int (co.paralleluniverse.pulsar.ChannelsHelper/receiveInt ~channel (long ~timeout) ~unit))))

(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size] (LongChannel/create size))
  ([] (LongChannel/create -1)))

(defmacro send-long
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (long ~message)))

(defmacro receive-long
  ([channel]
   `(long (co.paralleluniverse.pulsar.ChannelsHelper/receiveLong ~channel)))
  ([channel timeout unit]
   `(long (co.paralleluniverse.pulsar.ChannelsHelper/receiveLong ~channel (long ~timeout) ~unit))))

(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size] (FloatChannel/create size))
  ([] (FloatChannel/create -1)))

(defmacro send-float
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (float ~message)))

(defmacro receive-float
  ([channel]
   `(float (co.paralleluniverse.pulsar.ChannelsHelper/receiveFloat ~channel)))
  ([channel timeout unit]
   `(float (co.paralleluniverse.pulsar.ChannelsHelper/receiveFloat ~channel (long ~timeout) ~unit))))

(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size] (DoubleChannel/create size))
  ([] (DoubleChannel/create -1)))

(defmacro send-double
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (double ~message)))

(defmacro receive-double
  ([channel]
   `(double (co.paralleluniverse.pulsar.ChannelsHelper/receiveDouble ~channel)))
  ([channel timeout unit]
   `(double (co.paralleluniverse.pulsar.ChannelsHelper/receiveDouble ~channel (long ~timeout) ~unit))))


;; ## Actors

(defn ^Actor actor
  "Creates a new actor."
  ([^String name ^Integer mailbox-size f]
   (PulsarActor. name (int mailbox-size) (asSuspendableCallable f)))
  ([f]
   (PulsarActor. nil -1 (asSuspendableCallable f)))
  ([arg1 arg2]
   (let [[^String name ^Integer mailbox-size f] (ops-args [[string? nil] [integer? -1]] [arg1 arg2])]
     (PulsarActor. name (int mailbox-size) (asSuspendableCallable f)))))

(def self
  "@self is the currently running actor"
  (reify 
    clojure.lang.IDeref
    (deref [_] (Actor/currentActor))))

(defmacro spawn
  "Creates and starts a new actor"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.pulsar.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (.start fiber#)
       actor#)))

(defmacro spawn-link
  "Creates and starts a new actor, and links it to @self"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.pulsar.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (link! @self actor#)
       (start fiber#)
       actor#)))

(defmacro spawn-monitor
  "Creates and starts a new actor, and makes @self monitor it"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.pulsar.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (monitor! @self actor#)
       (start fiber#)
       actor#)))

(defn- process-receive-body 
  [body]
  (if (seq (filter #(= (first %) :else) (partition 2 body))) body (concat body '(:else PulsarActor/NO_MATCH))))

(defmacro receive
  ([]
   `(co.paralleluniverse.pulsar.PulsarActor/selfReceive))
  ([& body]
   (let [body (process-receive-body body)]
     `(co.paralleluniverse.pulsar.PulsarActor/selfReceive
       (suspendable! (fn [m#] 
                       (match m# ~@body)))))))

(defmacro receive-timed
  ([^Integer timeout]
   `(co.paralleluniverse.pulsar.PulsarActor/selfReceive ~timeout))
  ([^Integer timeout & body]
   (let [body (process-receive-body body)]
     `(co.paralleluniverse.pulsar.PulsarActor/selfReceive ~timeout
                                                          (suspendable! (fn [m#] 
                                                                          (match m# ~@body)))))))

(defmacro !
  "Sends a message to an actor"
  [actor message]
  `(co.paralleluniverse.pulsar.PulsarActor/send ~actor ~message))

(defmacro !!
  "Sends a message to an actor synchronously"
  [actor message]
  `(co.paralleluniverse.pulsar.PulsarActor/sendSync ~actor ~message))

(defn link!
  "links two actors"
  [^Actor actor1 ^Actor actor2]
  (.link actor1 actor2))

(defn unlink!
  "Unlinks two actors"
  [^Actor actor1 ^Actor actor2]
  (.unlink actor1 actor2))

(defn monitor!
  "Makes an actor monitor another actor. Returns a monitor object which should be used when calling demonitor."
  [^Actor actor1 ^Actor actor2]
  (.monitor actor1 actor2))

(defn demonitor!
  "Makes an actor stop monitoring another actor"
  [^Actor actor1 ^Actor actor2 monitor]
  (.demonitor actor1 actor2 monitor))





