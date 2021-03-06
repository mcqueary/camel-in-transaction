package org.apache.cmueller.camel.samples.camelone.xa;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

import javax.sql.DataSource;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.junit4.CamelSpringTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

public abstract class BaseJmsAndJdbcXATransactionSampleTest extends CamelSpringTestSupport {

    private JdbcTemplate jdbc;
    private TransactionTemplate transactionTemplate;
    
    private CountDownLatch latch;
    
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        
        DataSource ds = context.getRegistry().lookup("dataSource", DataSource.class);
        jdbc = new JdbcTemplate(ds);
        
        PlatformTransactionManager transactionManager = context.getRegistry().lookup("jtaTransactionManager", PlatformTransactionManager.class);
        transactionTemplate = new TransactionTemplate(transactionManager);
        
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                jdbc.execute("CREATE TABLE account (name VARCHAR(50), balance BIGINT)");
                jdbc.execute("INSERT INTO account VALUES('foo',1000)");
                jdbc.execute("INSERT INTO account VALUES('bar',1000)");
            }
        });
    }
    
    @After
    @Override
    public void tearDown() throws Exception {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                jdbc.execute("DROP TABLE account");
            }
        });
        
        super.tearDown();
    }
    
    private long queryForLong(final String query) {
        return transactionTemplate.execute(new TransactionCallback<Long>() {
            @Override
            public Long doInTransaction(TransactionStatus status) {
                return jdbc.queryForLong(query);
            }
        });
    }
    
    @Test
    public void moneyShouldBeTransfered() {
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
        
        template.sendBodyAndHeader("tibjms:queue:transaction.incoming.one" 
        		+ "?timeToLive=5000", new Long(100),
        		"JMS_TIBCO_PRESERVE_UNDELIVERED", true);
        
        Exchange exchange = consumer.receive("tibjms:queue:transaction.outgoing.one", 500000);
        assertNotNull(exchange);
        
        assertEquals(900, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1100, queryForLong("SELECT balance from account where name = 'bar'"));
    }
    
    @Test
    public void moneyShouldNotBeTransferred() {
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
        
        template.sendBodyAndHeader("tibjms:queue:transaction.incoming.two"
        		+"?timeToLive=5000", new Long(100),
        		"JMS_TIBCO_PRESERVE_UNDELIVERED", true);
        
        Exchange exchange = consumer.receive("tibjms:queue:$sys.undelivered", 500000);
        assertNotNull(exchange);
        
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
    }
    
    @Test
    public void moneyShouldNotBeTransferred2() throws Exception {
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
        
        template.sendBodyAndHeader("tibjms:queue:transaction.incoming.three"
        		+"?timeToLive=3000", new Long(100),
        		"JMS_TIBCO_PRESERVE_UNDELIVERED", true);
        
        Exchange exchange = consumer.receive("tibjms:queue:$sys.undelivered", 5000);
        assertNotNull(exchange);
        
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
    }
    
    @Test
    public void perfTest() throws Exception {
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
        
        // warm up
        latch = new CountDownLatch(100);
        for (int i = 0; i < 100; i++) {
            template.sendBody("tibjms:queue:transaction.incoming.four", new Long(0));
        }
        latch.await();
        
        latch = new CountDownLatch(1000);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            template.sendBody("tibjms:queue:transaction.incoming.four", new Long(1));
        }
        latch.await();
        long end = System.currentTimeMillis();

        System.out.println("duration: " + (end -start) + "ms");
        
        assertEquals(0, queryForLong("SELECT balance from account where name = 'foo'"));
        assertEquals(2000, queryForLong("SELECT balance from account where name = 'bar'"));
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("tibjmsXa:queue:transaction.incoming.one")
                    .transacted("PROPAGATION_REQUIRED")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .to("tibjmsXa:queue:transaction.outgoing.one");
                
                from("tibjmsXa:queue:transaction.incoming.two")
                    .transacted("PROPAGATION_REQUIRED")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .throwException(new SQLException("forced exception for test"))
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .to("tibjmsXa:queue:transaction.outgoing.two");
                
                from("tibjmsXa:queue:transaction.incoming.three")
                    .transacted("PROPAGATION_REQUIRED")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .throwException(new SQLException("forced exception for test"))
                    .to("tibjmsXa:queue:transaction.outgoing.three");
                
                from("tibjmsXa:queue:transaction.incoming.four")
	                .transacted("PROPAGATION_REQUIRED")
	                .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
	                .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
	                .to("tibjmsXa:queue:transaction.outgoing.four")
	                .process(new Processor() {
						@Override
						public void process(Exchange exchange) throws Exception {
							latch.countDown();
						}
					});
            }
        };
    }    
}