Probe
=========

Probe: systematic capture for dynamic program state

## Introduction

Text-based logging is an unfortunate historical artifact. A
traditional log statement serializes a portion of the program 
state into a human-readable string. While these strings are trivial 
to store, route, manipulate, and manually inspect - entire
public companies like Splunk are now dedicated to the infrastructure
needed to automate aggregation and analysis across collections of
systems.

In this library, a "probe" replaces the traditional log statement with
structured data that follows a few simple conventions. The library is
built on top of the Clojure 1.10+ feature "tap>" for compatibility
with tooling that leverages this interface. Of course if tap> is 
overloaded already with other state types you may find some 
incompatibility. 

We can call probe directly

    (probe..core/probe [:tag] {:message "Hello world" :location 2})
	
To top into the stream of probes, we can use a convenience facility

    (probe.core/add-named-tap :print #'prn)

The previous tap message will look something like this when printed

    => {:location 2, :message "Hello World", :probe/tags #{:ns/user},
	      :probe/ns :ns/user, :probe/thread-id 33, :probe/ts #inst "2023-10-24T05:44:55.924-00:00", 
	      :probe/line 7324}

The probe macro automatically captures the line and namespace of the 
statement, the thread id it was running on (for correlation) and the
current system time is was captured as well as the optional inclusion
of tags that can be used to filter and route probes.

The probe library also facilitates the creation of probe points into 
various dynamic contexts using existing facilities of Clojure. 

For example, you can capture changes to a state variable via watchers:

    (def my-state (atom nil))
    (probe.core/probe-state! [:atom] 'my-state)
	(reset! my-state {:message "Hello!"})
    => {:message "Hello", 
	      :probe/tags #{:ns/user}, 
		  :probe/ns :ns/user, :probe/thread-id 33, 
		  :probe/ts #inst "2023-10-25T04:52:14.757-00:00", 
		  :probe/line 7363}
	
You can also probe a function such as this not terribly useful recursive function:

    (defn test-fn2
              [value]
              (if (> value 10)
                (test-fn2 (/ value 2))
                (- value)))
    (probe-fn! [:tag] 'test-fn2)
    (test-fn2 10)
    => {:line 7388, :fname test-fn2, :args (20), :probe/tags #{:ns/probe.core :ns/probe :probe/fn :test :probe/fn-enter}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:17.929-00:00"}
    {:line 7388, :fname test-fn2, :args (10), :probe/tags #{:ns/probe.core :ns/probe :probe/fn :test :probe/fn-enter}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:17.929-00:00"}
    {:line 7388, :fname test-fn2, :args (10), :value -10, :probe/tags #{:ns/probe.core :probe/fn-exit :ns/probe :probe/fn :test}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:17.929-00:00"}
    {:line 7388, :fname test-fn2, :args (20), :value -10, :probe/tags #{:ns/probe.core :probe/fn-exit :ns/probe :probe/fn :test}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:17.929-00:00"}

This monkey patches the function to add probes to capture the input
values and the return values for each invocation of that function.

To reverse either of these probes, simply unprobe them.

    (unprobe-state! 'my-state)
    (unprobe-fn! 'test-fn2) 

If you want to go crazy, you can probe all the functions in a namespace:

    (probe-ns! 'user) ;; All public functions
	(probe-ns-all! 'user) ;; All private and public functions

Finally, you can probe the return value of an expression within a function:

    (defn test-fn2
              [value]
              (if (probe-expr (> value 10))
                (test-fn2 (/ value 2))
                (- value)))
    user> (test-fn2 20)
    {:form (do (> value 10)), :value true, :probe/tags #{:probe/expr :ns/probe.core :ns/probe}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:59.992-00:00"}
    {:form (do (> value 10)), :value false, :probe/tags #{:probe/expr :ns/probe.core :ns/probe}, :probe/ns :ns/probe.core, :probe/thread-id 33, :probe/ts #inst "2023-10-25T05:04:59.992-00:00"}    

## Debug style convenience functions

We created a series of debug-convention friendly macros so you can
insert standard debug, trace, warning, etc. levels of probes in your
code. 

    (error :message "Syntax error" :form string-expr :code *syntax-error-code*)
    
Which yields probe state like:

How about monitoring probe state across a distributed application?
Rather than using Scribe or Splunk to aggregate and parse text
strings, fire up [Riemann](http://riemann.io) and pipe probe state to
it or use a scalable data store like HBase, MongoDB, Cassandra, or
DynamoDB where historical probes can be indexed and analyzed as
needed?  Cassandra is especially nice as you can have it automatically
expire log data at different times based, perhaps, on a priority
field.

An alternative approach to this concept is
[Lamina](https://github.com/ztellman/lamina), introduced by a [nice
talk](http://vimeo.com/45132054#!) that closely shares the philosophy
behind Probe.  I wrote probe as a minimalist replacement for logging
infrastructure in a distributed application and think it is more
accessible than Lamina, but YMMV.  The more the merrier!

## Conventions and Lifecycle

The typical payload for a probe point is a map
If probe receives a value other than a map, it creates one
Probe points add: 
   Lexical context: :namespace :line :tags
   Dynamic context: :ts :thread-id

(probe map) ;; with {:tags ...}
(probe tags map)

probe elide (hide all probes w/ tagset X at compile time)

probe transform (can exclude/include by tags/namespaces/fns)
   Probe points that fail the filter don't dispatch and limit computation

probe transform (named transforms that can be applied to data)

probe subscription

probe point - Macro that elides probe points at compile time and generates
              code that captures lexical and runtime context into a map that follows
              a common convention and passes it to tap>

filter - Within the tap functions, filter and transform maps in preparation for a sink
subscription - subscribe to named 


## Installation

Add Probe to your lein project.clj :dependencies

```clojure
[com.vitalreactor/probe "1.0.0"]
```

And use it from your applications:

```clojure
(:require [probe.core :as p]
          [probe.sink :as sink]
          [probe.logging :as log])
```
Probe and log statements look like this:

```clojure
(p/probe [:tag] :msg "This is a test" :value 10)
(log/info :msg "This is a test" :value 10)
```

See the examples below for details on how probe state is generated by
these statements, how to create sinks, and how to route probe state to
sinks with an optional transform channel.

## Concepts

* Probe statement - Any program statement that extracts dynamic state during
  program execution. A probe will typically return some subset of
  lexical, dynamic, and/or host information as well as explicit
  user-provided data.  Probes can include function
  entry/exit/exception events as well or tie into foundational notification
  mechanisms such as adding a 'watcher probe' to an agent or atom.
   * Function probe - Default probes that can be added to any Var holding a function value
   * Watcher probe - Probe state changes on any Atom, Ref, Var, or Agent.
* Probe state - A kv-map generated by a probe statement
* Tags - Probe expressions accept one or more tags to help filter and route state.
    Built-in probe statements generate a specific set of tags.
* Sink - A function that takes probe state values and disposes them in some
    way, typically to a logging subsystem, the console, memory or a database.
* Subscriptions - This is the library's glue, consisting of a Selector, an
    optional core.async Channel, and a sink name.
* Selector - a conjunction of tags that must be present for the probe state to
    be pushed onto the channel and on to the sink.

Reserved state keys

- Probe statements: :probe/thread-id, :probe/tags, :probe/ns, :probe/line, :probe/ts
- Expression probe: :probe/expr, :probe/value
- Function probes:  :probe/fname, :probe/fn, :probe/args, :probe/return, :probe/exception
- Logging keys:     :probe/level 
- Host info keys:   :probe/ip :probe/host :probe/pid

Reserved tags:

- Namespace tags: :ns/*
- Function probes: :probe/fn, :probe/fn-exit, :probe/fn-enter, :probe/fn-except
- Watcher probes: :probe/watch
- Standard logging levels: :trace, :debug, :info, :warn, :error

```

## Development Ideas

### Major Tasks

* Injest legacy logging messages - Most systems will have legacy libraries that
     use one of the Java logging systems.  Create some namespaces that
     allow for injecting these log messages into clj-probe middleware.  Ignore
     any that we inject using the log sink.  This may be non-trivial.
* Adapt the function probes to collect profile information
* Record the stack state at a probe point
* Add higher level and dynamic context targeted function tracing / collection facilities
  (e.g. trace 100 input/result vectors from function f or namespace ns)

## Minor Tasks

* Add a circular buffer to capture latest state (add-tap-history n) w/ convenience functions for last-tap, last-n-taps 
* Add a JSON file tap
* Add a clojure EDN file tap
* Add metadata so we can introspect what functions are probed
* Add a Riemann sink as probe.riemann using the Riemann clojure client
* Add a Cassandra sink


