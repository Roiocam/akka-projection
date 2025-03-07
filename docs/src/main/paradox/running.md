# Running a Projection

Once you have decided how you want to build your projection, the next step is to run it. Typically, you run it in a distributed fashion in order to spread the load over the different nodes in an Akka Cluster. However, it's also possible to run it as a single instance (when not clustered) or as single instance in a Cluster Singleton.

## Dependencies

To distribute the projection over the cluster we recommend the use of [ShardedDaemonProcess](https://doc.akka.io/docs/akka/current/typed/cluster-sharded-daemon-process.html). Add the following dependency in your project if not yet using Akka Cluster Sharding:

@@dependency [sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-sharding-typed_$scala.binary.version$
  version=$akka.version$
}

Akka Projections require Akka $akka.version$ or later, see @ref:[Akka version](overview.md#akka-version).

For more information on using Akka Cluster consult Akka's reference documentation on [Akka Cluster](https://doc.akka.io/docs/akka/current/typed/index-cluster.html) and [Akka Cluster Sharding](https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html).

## Running with Sharded Daemon Process

The Sharded Daemon Process can be used to distribute `n` instances of a given Projection across the cluster. Therefore, it's important that each Projection instance consumes a subset of the stream of envelopes.

How the subset is created depends on the kind of source we consume. If it's an Alpakka Kafka source, this is done by Kafka consumer groups. When consuming from Akka Persistence Journal, the events must be sliced by tagging them as demonstrated in the example below.

### Tagging Events in EventSourcedBehavior

Before we can consume the events, the `EventSourcedBehavior` must tag the events with a slice number.

Scala
:  @@snip [ShoppingCart.scala](/examples/src/test/scala/docs/eventsourced/ShoppingCart.scala) { #imports #slicingTags #tagging }

Java
:  @@snip [ShoppingCart.java](/examples/src/test/java/jdocs/eventsourced/ShoppingCart.java) { #slicingTags #tagging }

In the above example, we created a @scala[`Vector[String]`]@java[`List<String>`] of tags from *carts-0* to *carts-4*. Each entity instance will tag its events using one of those tags. The tag is selected based on the modulo of the entity id's hash code (stable identifier) and the total number of tags. As a matter of fact, this will create a journal sliced with different tags (ie: from *carts-0* to *carts-4*). @scala[Note the `.withTagger` in the initialization of the `EventSourcedBehavior`.]

The number of tags should be more than the number of nodes that you want to distribute the load over. It's not easy
to change this afterwards without system downtime. Fewer tags than the number of nodes will result in not hosting a
Projection instance on some nodes. More tags than the number of nodes means that each node is hosting more than one
Projection instance, which is fine. It's good to start with more tags than nodes to have some room for scaling out
to more nodes later if needed. As a rule of thumb, the number of tags should be a factor of ten greater than the
planned maximum number of cluster nodes. It doesn't have to be exact.

We will use those tags to query the journal and create as many Projections instances, and distribute them in the cluster.

@@@ warning
When using [Akka Persistence Cassandra plugin](https://doc.akka.io/docs/akka-persistence-cassandra/current/) you should
not use too many tags for each event. Each tag will result in a copy of the event in a separate table and
that can impact write performance. Typically, you would use 1 tag per event as illustrated here. Additional
filtering of events can be done in the Projection handler if it doesn't have to act on certain events.
The [JDBC plugin](https://doc.akka.io/docs/akka-persistence-jdbc/current/) 
don't have this constraint.
@@@

See also the [Akka reference documentation for tagging](https://doc.akka.io/docs/akka/current/typed/persistence.html#tagging).

### Event Sourced Provider per tag

We can use the @ref:[EventSourcedProvider](eventsourced.md) to consume the `ShoppingCart` events.

Scala
:  @@snip [CassandraProjectionDocExample.scala](/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala) { #source-provider-imports #running-source-provider }

Java
:  @@snip [CassandraProjectionDocExample.java](/examples/src/it/java/jdocs/cassandra/CassandraProjectionDocExample.java) { #source-provider-imports #running-source-provider }

Note that we define a method that builds a new `SourceProvider` for each passed `tag`.

### Building the Projection instances

Next we create a method to return Projection instances. Again, we pass a tag that is used to initialize the `SourceProvider` and as the key in `ProjectionId`.

Scala
:  @@snip [CassandraProjectionDocExample.scala](/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala) { #projection-imports #running-projection }

Java
:  @@snip [CassandraProjectionDocExample.java](/examples/src/it/java/jdocs/cassandra/CassandraProjectionDocExample.java) { #projection-imports #running-projection }

### Initializing the Sharded Daemon

Once we have the tags, the `SourceProvider` and the `Projection` of our choice, we can glue all the pieces together using the Sharded Daemon Process and let it be distributed across the cluster.

Scala
:  @@snip [CassandraProjectionDocExample.scala](/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala) { #daemon-imports #running-with-daemon-process }

Java
:  @@snip [CassandraProjectionDocExample.java](/examples/src/it/java/jdocs/cassandra/CassandraProjectionDocExample.java) { #daemon-imports #running-with-daemon-process }

For this example, we configure as many `ShardedDaemonProcess` as tags and we define the behavior factory to return `ProjectionBehavior` wrapping each time a different `Projection` instance. Finally, the `ShardedDaemon` is configured to use the `ProjectionBehavior.Stop` as its control stop message.

For graceful stop it is recommended to use @scala[`ProjectionBehavior.Stop`]@java[`ProjectionBehavior.stop()`] message.

### Projection Behavior

The `ProjectionBehavior` is an Actor `Behavior` that knows how to manage the Projection lifecyle. The Projection starts to consume the events as soon as the actor is spawned and will restart the source in case of failures (see @ref:[Projection Settings](projection-settings.md)).

For graceful stop it is recommended to use @scala[`ProjectionBehavior.Stop`]@java[`ProjectionBehavior.stop()`] message.

## Running with local Actor

You can spawn the `ProjectionBehavior` as any other `Behavior`. This can be useful for testing or when running
a local `ActorSystem` without Akka Cluster.

Scala
:  @@snip [CassandraProjectionDocExample.scala](/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala) { #running-with-actor }

Java
:  @@snip [CassandraProjectionDocExample.java](/examples/src/it/java/jdocs/cassandra/CassandraProjectionDocExample.java) { #running-with-actor }

Be aware of that the projection and its offset storage is based on the `ProjectionId`. If more than one instance with the same `ProjectionId` are running concurrently they will
overwrite each others offset storage with undefined and unpredictable results.

## Running in Cluster Singleton

If you know that you only need one or a few projection instances an alternative to @ref:[Sharded Daemon Process](#running-with-sharded-daemon-process)
is to use [Akka Cluster Singleton](https://doc.akka.io/docs/akka/current/typed/cluster-singleton.html)  

Scala
:  @@snip [CassandraProjectionDocExample.scala](/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala) { #running-with-singleton }

Java
:  @@snip [CassandraProjectionDocExample.java](/examples/src/it/java/jdocs/cassandra/CassandraProjectionDocExample.java) { #singleton-imports #running-with-singleton }

Be aware of that all projection instances that are running with Cluster Singleton will be running on the same node
in the Cluster.
