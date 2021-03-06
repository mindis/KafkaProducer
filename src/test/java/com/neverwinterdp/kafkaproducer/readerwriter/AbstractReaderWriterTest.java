package com.neverwinterdp.kafkaproducer.readerwriter;

import static com.neverwinterdp.kafkaproducer.util.Utils.printRunningThreads;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import kafka.common.FailedToSendMessageException;
import kafka.server.KafkaServer;

import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkTimeoutException;
import org.apache.log4j.Logger;
import org.junit.BeforeClass;
import org.junit.Test;

import com.neverwinterdp.kafkaproducer.reader.KafkaReader;
import com.neverwinterdp.kafkaproducer.retry.DefaultRetryStrategy;
import com.neverwinterdp.kafkaproducer.retry.RunnableRetryer;
import com.neverwinterdp.kafkaproducer.servers.EmbeddedCluster;
import com.neverwinterdp.kafkaproducer.util.HostPort;
import com.neverwinterdp.kafkaproducer.util.TestUtils;
import com.neverwinterdp.kafkaproducer.util.ZookeeperHelper;
import com.neverwinterdp.kafkaproducer.writer.KafkaWriter;
import com.neverwinterdp.kafkaproducer.writer.TestKafkaWriter;

public abstract class AbstractReaderWriterTest {

  
  static {
    System.setProperty("log4j.configuration", "file:src/test/resources/log4j.properties");
  }

  protected static final Logger logger = Logger.getLogger(TestKafkaWriter.class);
  protected static EmbeddedCluster cluster;
  protected static ZookeeperHelper helper;
  protected static String zkURL;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    printRunningThreads();
  }

  protected float tolerance = 0.95f;

  protected void initCluster(int numOfZkInstances, int numOfKafkaInstances) throws Exception {
    cluster = new EmbeddedCluster(numOfZkInstances, numOfKafkaInstances);
    cluster.start();

    zkURL = cluster.getZkURL();
    helper = new ZookeeperHelper(zkURL);
    Thread.sleep(3000);
  }

  private void writeAndRead() throws Exception {
    String topic = TestUtils.createRandomTopic();
    helper.createTopic(topic, 1, 1);
    Properties props = initProperties();
    KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).build();
    writer.write("message");
    KafkaReader reader = new KafkaReader(zkURL, topic, 0);

    List<String> messages = reader.read();
    assertEquals(messages.size(), 1);
    reader.close();
  }

  protected abstract Properties initProperties() throws Exception;

  @Test(expected = IndexOutOfBoundsException.class)
  public void testNoServerRunning() throws Exception {
    try {
      initCluster(0, 0);
      writeAndRead();
    } finally {
      // cluster.shutdown();
    }

  }

  @Test(expected = ZkNoNodeException.class)
  public void testOnlyZookeeperRunning() throws Exception {
    try {
      initCluster(1, 0);
      writeAndRead();
    } finally {
      cluster.shutdown();
    }
  }

  @Test(expected = ZkTimeoutException.class)
  public void testOnlyBrokerRunning() throws Exception {
    try {
      initCluster(0, 1);
      writeAndRead();
    } finally {
      // cluster.shutdown();
    }
  }

  @Test(expected = NullPointerException.class)
  public void testWriteToNonExistentTopic() throws Exception {
    writeToNonExistentTopic();
  }

  public void writeToNonExistentTopic() throws Exception {
    try {
      initCluster(1, 1);
      Properties props = initProperties();
      KafkaWriter writer = new KafkaWriter.Builder(zkURL, "someTopic").properties(props).partition(99).build();
      writer.write("my message");
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testWriteToWrongServer() throws Exception {

    try {
      initCluster(1, 2);
      String topic = TestUtils.createRandomTopic();
      helper.createTopic(topic, 2, 1);
      Properties props = initProperties();
      Collection<HostPort> brokers = helper.getBrokersForTopic(topic).values();
      killLeader(topic);
      brokers = helper.getBrokersForTopic(topic).values();
      KafkaWriter writer = new KafkaWriter.Builder(brokers, topic).properties(props).build();
      writer.write("my message");
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testCheckWritenDataExistOnPartition() throws Exception {
    try {
      initCluster(1, 1);
      String topic = TestUtils.createRandomTopic();
      helper.createTopic(topic, 3, 1);
      // helper.addPartitions(topic, 2);
      Properties props = initProperties();
      for (int i = 0; i < 3; i++) {
        KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).partition(i).build();
        writer.write("message" + i);
      }

      for (int i = 0; i < 3; i++) {
        Thread.sleep(5000);
        KafkaReader reader = new KafkaReader(zkURL, topic, i);

        List<String> messages = new LinkedList<String>();
        while (reader.hasNext()) {
          messages.addAll(reader.read());
        }
        assertEquals(messages.size(), 1);
        assertEquals("message" + i, messages.get(0));
        reader.close();

      }

    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testWriteToSingleTopicSinglePartition() throws Exception {
    try {
      initCluster(1, 1);
      String topic = TestUtils.createRandomTopic();
      helper.createTopic(topic, 3, 1);
      Properties props = initProperties();
      KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).partition(0).build();
      writer.write("message");
      KafkaReader reader;
      reader = new KafkaReader(zkURL, topic, 0);
      Thread.sleep(5000);
      List<String> messages = reader.read();
      assertEquals(messages.size(), 1);
      assertEquals(messages.get(0), "message");
      for (int i = 1; i < 3; i++) {
        reader = new KafkaReader(zkURL, topic, i);
        messages = reader.read();
        assertEquals(messages.size(), 0);
      }
      reader.close();
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testWriteTenThousandMessages() throws Exception {
    try {
      initCluster(1, 1);
      String topic = TestUtils.createRandomTopic();
      helper.createTopic(topic, 3, 1);
      Properties props = initProperties();
      KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).partition(0).build();
      for (int i = 0; i < 10000; i++)
        writer.write("message" + i);
      KafkaReader reader;
      reader = new KafkaReader(zkURL, topic, 0);
      List<String> messages = new LinkedList<String>();
      ;
      while (reader.hasNext()) {
        messages.addAll(reader.read());
      }
      assertEquals(messages.size(), 10000);

      for (int i = 0; i < 10000; i++) {
        assertEquals(messages.get(i), "message" + i);
      }
      reader.close();
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testWriteTenThousandMessagesToFiveTopics() throws Exception {
    try {
      initCluster(1, 1);
      String[] topics = new String[5];
      Properties props = initProperties();
      for (int i = 0; i < 5; i++)
        topics[i] = TestUtils.createRandomTopic();
      for (int i = 0; i < 5; i++) {
        helper.createTopic(topics[i], 1, 1);
        KafkaWriter writer = new KafkaWriter.Builder(zkURL, topics[i]).properties(props).partition(0).build();
        for (int j = 0; j < 10000; j++)
          writer.write("message" + j);
      }
      for (int i = 0; i < 5; i++) {
        KafkaReader reader;
        reader = new KafkaReader(zkURL, topics[i], 0);
        List<String> messages = new LinkedList<String>();

        while (reader.hasNext()) {
          messages.addAll(reader.read());
          Thread.sleep(3000);
        }
        assertEquals(messages.size(), 10000);

        for (int j = 0; j < 10000; j++) {
          assertEquals(messages.get(j), "message" + j);
        }
        messages.clear();
        reader.close();
      }

    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testWriteTenThousandMessagesToFiveTopicsTowPartition() throws Exception {
    try {
      initCluster(1, 1);

      String[] topics = new String[5];
      for (int i = 0; i < 5; i++)
        topics[i] = TestUtils.createRandomTopic();
      int[] partitions = { 0, 1 };
      Properties props = initProperties();
      for (int i = 0; i < 5; i++) {
        helper.createTopic(topics[i], 2, 1);
        for (int partition : partitions) {
          KafkaWriter writer = new KafkaWriter.Builder(zkURL, topics[i]).properties(props).partition(partition).build();
          for (int j = 0; j < 5000; j++)
            writer.write("message" + j);
        }
      }

      for (int i = 0; i < 5; i++) {
        KafkaReader reader;
        for (int partition : partitions) {
          reader = new KafkaReader(zkURL, topics[i], partition);
          List<String> messages = new LinkedList<String>();

          while (reader.hasNext()) {
            messages.addAll(reader.read());
          }
          assertEquals(messages.size(), 5000);

          for (int j = 0; j < 5000; j++) {
            assertEquals(messages.get(j), "message" + j);
          }

          reader.close();
          messages.clear();
        }
      }

    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testRetryUntilKafkaStart() throws Exception {
    List<String> messages = new ArrayList<>();
    try {
      initCluster(1, 0);
      Properties props = initProperties();

      final RunnableRetryer retryer;
      final String topic = TestUtils.createRandomTopic();

      KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).build();

      retryer = new RunnableRetryer(new DefaultRetryStrategy(10, 3000, FailedToSendMessageException.class), writer);
      new Thread(new Runnable() {

        @Override
        public void run() {
          int retries = 0;
          do {
            retries = retryer.getRetryStrategy().getRetries();
          } while (retries < 1);
          System.out.println("Starting Kafka");
          cluster.addKafkaServer();
          helper.createTopic(topic, 1, 1);

        }
      }).start();
      retryer.run();
      Thread.sleep(5000);
      KafkaReader reader = new KafkaReader(zkURL, topic, 0);
      messages = reader.read();
      reader.close();
      System.err.println("messages size " + messages.size());
      assertEquals(1, messages.size());

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      cluster.shutdown();
    }

  }

 @Test
  public void testRetryUntilTopicCreation() throws Exception {
    List<String> messages = new ArrayList<>();
    try {
      initCluster(1, 1);
      Properties props = initProperties();

      final RunnableRetryer retryer;
      final String topic = TestUtils.createRandomTopic();

      KafkaWriter writer = new KafkaWriter.Builder(zkURL, topic).properties(props).build();

      retryer = new RunnableRetryer(new DefaultRetryStrategy(10, 3000, FailedToSendMessageException.class), writer);
      new Thread(new Runnable() {

        @Override
        public void run() {
          int retries = 0;
          do {
            retries = retryer.getRetryStrategy().getRetries();
            System.out.println("retries " + retries);
          } while (retries < 1);
          System.out.println("Creating Topic");
          helper.createTopic(topic, 1, 1);

        }
      }).start();
      retryer.run();
      Thread.sleep(5000);
      KafkaReader reader = new KafkaReader(zkURL, topic, 0);
      messages = reader.read();
      reader.close();
      System.err.println("messages size " + messages.size());
      assertEquals(1, messages.size());

    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      cluster.shutdown();
    }

  }

  @Test
  public void testRetryWhenLeaderKilled() throws Exception {
    try {
      initCluster(1, 3);
      List<String> messages = new ArrayList<>();
      RunnableRetryer retryer;
      final String topic = TestUtils.createRandomTopic();
      helper.createTopic(topic, 1, 3);
      Properties props = initProperties();
      KafkaWriter.Builder builder = new KafkaWriter.Builder(zkURL, topic).properties(props);
      KafkaWriter writer = new KafkaWriter(builder) {
        public void run() {
          for (int i = 0; i < 10000; i++) {
            try {
              write("message" + i);
              if (i == 9)
                killLeader(topic);
            } catch (Exception e) {
              System.out.println("Exception " + e);

            }
          }
        }

      };
      retryer = new RunnableRetryer(new DefaultRetryStrategy(5, 500, FailedToSendMessageException.class), writer);
      retryer.run();
      messages = TestUtils.readMessages(topic, zkURL);
      System.out.println("messages.size() " + messages.size());
      assertTrue( messages.size() >= 10000 * tolerance);
    } finally {
      cluster.shutdown();
    }

  }

  @Test
  public void testKillLeaderAndRebalance() throws Exception {

    int kafkaBrokers = 4;
    final int replicationFactor = 3;
    initCluster(1, kafkaBrokers);

    final String topic = TestUtils.createRandomTopic();
    try {
      helper.createTopic(topic, 1, replicationFactor);
      final HostPort leader = helper.getLeaderForTopicAndPartition(topic, 0);
      Properties props = initProperties();
      KafkaWriter.Builder builder = new KafkaWriter.Builder(zkURL, topic).partition(0).properties(props);
      
      KafkaWriter writer = new KafkaWriter(builder) {
        public void run() {
          for (int i = 0; i < 10000; i++) {
            write("message" + i);
            if (i == 9999) {
              System.err.println("End of send" );
            }
            if (i == 10) {
              new Thread(new Runnable() {

                @Override
                public void run() {

                  try {
                    killLeader(leader);
                    List<Object> remainingBrokers = new ArrayList<>();
                    for (KafkaServer server : cluster.getKafkaServers()) {
                      remainingBrokers.add(server.config().brokerId());
                    }
                    System.err.println("Leader killed  " );
                    int brokersForTopic = helper.getBrokersForTopicAndPartition(topic, 0).size();
                    assertEquals(replicationFactor - 1, brokersForTopic);
                    helper.rebalanceTopic(topic, 0, remainingBrokers);
                    System.err.println("rebalance done " );

                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                }
              }).start();
            }

          }
        }

      };
      RunnableRetryer retryer = new RunnableRetryer(
          new DefaultRetryStrategy(5, 500, FailedToSendMessageException.class), writer);
      retryer.run();
      Thread.sleep(10000);
      KafkaReader reader;
      reader = new KafkaReader(zkURL, topic, 0);
      List<String> messages = new LinkedList<String>();

      int total = 0;
      while (reader.hasNext()) {
        messages = reader.read();
        total += messages.size();
        
      }
      System.out.println("Total is  " + total);
      assertTrue(total >= 10000 * tolerance);
      messages.clear();
      reader.close();
    } finally {
      helper.close();
      cluster.shutdown();
    }
  }

  @Test
  public void testKillLeaderRebalnceAndRestart() throws Exception {

    int kafkaBrokers = 4;
    final int replicationFactor = 3;
    initCluster(1, kafkaBrokers);

    final String topic = TestUtils.createRandomTopic();
    try {
      helper.createTopic(topic, 1, replicationFactor);
      final HostPort leader = helper.getLeaderForTopicAndPartition(topic, 0);
      Properties props = initProperties();
      KafkaWriter.Builder builder = new KafkaWriter.Builder(zkURL, topic).partition(0).properties(props);
      
      KafkaWriter writer = new KafkaWriter(builder) {
        public void run() {
          for (int i = 0; i < 10000; i++) {
            write("message" + i);
            if (i == 9999) {
              System.err.println("End of send" );
            }
            if (i == 10) {
              new Thread(new Runnable() {

                @Override
                public void run() {

                  try {
                    KafkaServer deadLeader = killLeader(leader);
                    List<Object> remainingBrokers = new ArrayList<>();
                    for (KafkaServer server : cluster.getKafkaServers()) {
                      remainingBrokers.add(server.config().brokerId());
                    }
                    System.err.println("Leader killed  " );
                    // before rebalance the shouldn't be equal
                    int brokersForTopic = helper.getBrokersForTopicAndPartition(topic, 0).size();
                    assertEquals(replicationFactor - 1, brokersForTopic);
                    helper.rebalanceTopic(topic, 0, remainingBrokers);
                    System.err.println("rebalance done " );
                    brokersForTopic = helper.getBrokersForTopicAndPartition(topic, 0).size();
                    deadLeader.startup();
                    cluster.getKafkaServers().add(deadLeader);
                    System.err.println("dead leader started" );
                    remainingBrokers.clear();
                    for (KafkaServer server : cluster.getKafkaServers()) {
                      remainingBrokers.add(server.config().brokerId());
                    }
                    helper.rebalanceTopic(topic, 0, remainingBrokers);
                    System.err.println("Rebalance again done " );
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                }
              }).start();
            }

          }
        }

      };
      RunnableRetryer retryer = new RunnableRetryer(
          new DefaultRetryStrategy(5, 500, FailedToSendMessageException.class), writer);
      retryer.run();
      Thread.sleep(10000);
      KafkaReader reader;
      reader = new KafkaReader(zkURL, topic, 0);
      List<String> messages = new LinkedList<String>();

      int total = 0;
      while (reader.hasNext()) {
        messages = reader.read();
        total += messages.size();
        
      }
      System.out.println("Total is  " + total);
      assertTrue(total >= 10000 * tolerance);
      messages.clear();
      reader.close();
    } finally {
      helper.close();
      cluster.shutdown();
    }
  }

  protected void killLeader(String topic) throws Exception {
    // while writer threads are writing, kill the leader
    HostPort leader = helper.getLeaderForTopicAndPartition(topic, 0);
    for (KafkaServer server : cluster.getKafkaServers()) {
      if (leader.getHost().equals(server.config().hostName()) && leader.getPort() == server.config().port()) {
        server.shutdown();
        server.awaitShutdown();
        System.out.println("Shutting down current leader --> " + server.config().hostName() + ":"
            + server.config().port());
      }
    }
  }

  protected KafkaServer killLeader(HostPort leader) throws Exception {

    KafkaServer kafkaServer = null;
    for (KafkaServer server : cluster.getKafkaServers()) {
      if (leader.getHost().equals(server.config().hostName()) && leader.getPort() == server.config().port()) {
        server.shutdown();
        server.awaitShutdown();

        kafkaServer = server;
        System.out.println("Shutting down current leader --> " + server.config().hostName() + ":"
            + server.config().port() + " id " + server.config().brokerId());
      }
    }

    cluster.getKafkaServers().remove(kafkaServer);
    return kafkaServer;
  }

}
