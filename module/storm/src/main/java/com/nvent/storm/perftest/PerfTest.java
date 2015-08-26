package com.nvent.storm.perftest;

import com.nvent.kafka.perftest.KafkaMessageGenerator;
import com.nvent.kafka.perftest.KafkaMessageValidator;
import com.nvent.tool.message.Message;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.StormTopology;
import backtype.storm.topology.TopologyBuilder;

public class PerfTest {
  private PerfTestConfig config ;
  
  public PerfTest(PerfTestConfig config ) {
    this.config = config ;
  }
  
  private TopologyBuilder createTopologyBuilder() {
    TopologyBuilder builder = new TopologyBuilder();
    KafkaTopicSpout<Message> messageSpout = 
        new KafkaTopicSpout<Message>("PerfTestSpout", config.zkConnect, config.topicIn, Message.class);
    builder.setSpout("spout", messageSpout, 5);
    builder.setBolt("split",  new SplitStream(), 5).shuffleGrouping("spout");
    
    for(int i = 0; i < config.numOPartition; i++) {
      SaveStreamToKafka saveP = 
        new SaveStreamToKafka("save.partition-" + i, config.kafkaConnect, "partition-" + i) ;
      builder.setBolt("save.partition-" + i, saveP, 5).shuffleGrouping("split", "partition-" + i);
    }
    
    SaveStreamToKafka saveAll = new SaveStreamToKafka("save.all", config.kafkaConnect, config.topicOut) ;
    builder.setBolt("save.all", saveAll, 5).shuffleGrouping("split", "all");

    return builder;
  }
  
  public void submit(LocalCluster cluster) throws Exception {
    TopologyBuilder builder = createTopologyBuilder();
    Config conf = new Config();
    conf.setDebug(true);
    StormTopology topology = builder.createTopology();
    cluster.submitTopology(config.stormTopologyName, conf, topology);
  }
  
  public void submit() throws Exception {
    System.setProperty("storm.jar", config.stormJarFiles);
    Config conf = new Config();
    conf.put(Config.NIMBUS_HOST, config.stormNimbusHost);
    conf.setNumWorkers(10);
    conf.setMaxSpoutPending(5000);
    TopologyBuilder builder = createTopologyBuilder();
    StormTopology topology = builder.createTopology();
    StormSubmitter.submitTopologyWithProgressBar(config.stormTopologyName, conf, topology);
  }
  
  static public void main(String[] args) throws Exception {
    PerfTestConfig config = new PerfTestConfig(args);

    KafkaMessageGenerator messageGenerator = 
      new KafkaMessageGenerator(config.kafkaConnect, config.topicIn, config.numOPartition, config.numOfMessagePerPartition);
    messageGenerator.run();
    messageGenerator.waitForTermination(36000000);
    
    LocalCluster localCluster = null;
    
    PerfTest perfTest = new PerfTest(config);
    if(config.stormNimbusHost == null) {
      localCluster = new LocalCluster("localhost", new Long(2181));
      perfTest.submit(localCluster);
    } else {
      perfTest.submit() ;
    }
    
    Thread.sleep(15000);

    System.out.println("Perf Test Generator Report:") ;
    System.out.println(messageGenerator.getTrackerReport()) ;
    
    KafkaMessageValidator validator =
      new KafkaMessageValidator(config.zkConnect, config.topicOut, config.numOPartition, config.numOfMessagePerPartition);
    validator.run();
    validator.waitForTermination(3600000);
    System.out.println("Perf Test Validator Report:") ;
    System.out.println(validator.getTrackerReport()) ;
    
    if(localCluster != null) localCluster.shutdown();
  }
}
