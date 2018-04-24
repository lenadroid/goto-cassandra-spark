# goto-cassandra-spark

A Spark job that outputs the ranked list of episode to watch based on one preference "keyword". Results of previous searches are stored in Cassandra.

## What this Job is supposed to do

This Spark job is a prototype of a trivial recommendation system for TV-show episodes to watch based on your preference.

### Input requirements

* A directory with text files containing dialogie scripts for each episode of the show. Current scripts are from the 1st season of "The Game of Thrones". It is absolutely okay to replace existing files in the `moviescript` directory with dialogue scripts from the TV-show of your choice, or even a series of book scripts.
* A keyword of preference. It can be a favorite show character name, or a favorite thing that gets mentioned throughout the dialogue. For example, `tyrion`, `arya`, `nymeria` or `cersei` would all be valid things to have as a keyword of preference.
* Cassandra Stateful Set running in your Kubernetes cluster.

## Get Cassandra Stateful Set running

You may use the following instructions to get Cassandra Stateful Set running before following next steps:

[Running Cassandra on Kubernetes on Azure](https://lenadroid.github.io/posts/stateful-sets-kubernetes-azure.html)

[Deploying Cassandra with Stateful Sets](https://kubernetes.io/docs/tutorials/stateful-application/cassandra/)

## Prepare Spark container image

Read through these pages to learn about building your spark image:

[Running Apache Spark jobs on Azure Kubernetes Service](https://docs.microsoft.com/en-us/azure/aks/spark-job?WT.mc_id=sparkaks-blog-alehall)

[Running Spark on Kubernetes](https://spark.apache.org/docs/latest/running-on-kubernetes.html)

### Expected output

When the job is completed, it should output a ranked list of episodes (or books, or etc.) corresponding to the specified keyword of preference sorted from most preferable to least preferable. For example, in case the analysis is done for `tyrion` as a keyword of preference, and the `moviescript` directory contains dialogies for episodes of the first season of Game of Thrones, the ranked list of episodes will look similar to following:

```
1 -> file:/opt/spark/data/moviescript/8.The Pointy End.txt
2 -> file:/opt/spark/data/moviescript/6.A Golden Crown.txt
3 -> file:/opt/spark/data/moviescript/1.Winter is Coming.txt
4 -> file:/opt/spark/data/moviescript/3.Lord Snow.txt
5 -> file:/opt/spark/data/moviescript/5.The Wolf and the Lion.txt
6 -> file:/opt/spark/data/moviescript/7.You Win or You Die.txt
```

In case the keyword of preference isn't mentioned in every episode, the ranked list wil me smaller than the original list.

## Steps

There are many ways to run this Spark job, depending on resource scheduler and other criteria. These instructions are based on what I've done to run the job using the Spark's new option for Kubernetes scheduler (starting from 2.3 release) with Cassandra Stateful Set. There are more options how to run it than described here!

### Package current Scala project in a `jar` file

Clone the project and navigate into it.

```
git clone git@github.com:lenadroid/goto-cassandra-spark.git
cd goto-cassandra-spark
export RECOMMENDER=$(pwd)
```

Make a `jar`.
```
sbt assembly
```

## Before building Spark source

### Copy Movie Scripts to $SPARK_HOME/data directory

Or upload them to somewhere remote where you can read it from using `sc.wholeTextFiles("...movie scripts directory address...")`.

```
cp $RECOMMENDER/moviescript $SPARK_HOME/data/moviescript
```

### Copy a `jar` under $SPARK_HOME directory

```
cp $RECOMMENDER/target/scala-2.11/GotoKubeCassandraSpark-assembly-0.1.0-SNAPSHOT.jar $SPARK_HOME/GotoKubeCassandraSpark-assembly-0.1.0-SNAPSHOT.jar
```

### Add a line to Dockerfile

Open `Dockerfile` under `$SPARK_HOME/resource-managers/kubernetes/docker/src/main/dockerfiles/spark/Dockerfile`

Add a line to add a `jar` file:
```
...
WORKDIR /opt/spark/work-dir

ADD GotoKubeCassandraSpark-assembly-0.1.0-SNAPSHOT.jar /opt/spark/jars/GotoKubeCassandraSpark-assembly-0.1.0-SNAPSHOT.jar

ENTRYPOINT [ "/opt/entrypoint.sh" ]
```

Note: dependencies can be either pre-mounted into the Spark container image itself, or remote. More details [here](https://spark.apache.org/docs/latest/running-on-kubernetes.html).

## Build Spark

```bash
git clone -b branch-2.3 https://github.com/apache/spark
cd spark
sparkdir=$(pwd)
export JAVA_HOME=`/usr/libexec/java_home -d 64 -v "1.8*"`
./build/mvn -Pkubernetes -DskipTests clean package
```

### Build and push Spark docker image to some container repo

```
export CONTAINER_REGISTRY="..."
./bin/docker-image-tool.sh -r $CONTAINER_REGISTRY -t goto-cassandra-spark build

./bin/docker-image-tool.sh -r $CONTAINER_REGISTRY -t goto-cassandra-spark push
```

## Submission of a Spark job

Start Kubernetes proxy.
```
kubectl proxy
```

### Choose a keyword

```
export KEYWORD="tyrion"
```

### Let's run a job!

```bash
./bin/spark-submit \
--master k8s://http://127.0.0.1:8001 \
--deploy-mode cluster \
--name goto-cassandra-spark \
--class lena.GotoKubeCassandraSpark \
--conf spark.kubernetes.container.image=lenadroid/spark:goto-cassandra-spark  \
--jars https://lenadroid.blob.core.windows.net/jars/spark-cassandra-connector_2.11-2.0.7.jar \
local:///opt/spark/jars/GotoKubeCassandraSpark-assembly-0.1.0-SNAPSHOT.jar $KEYWORD
```