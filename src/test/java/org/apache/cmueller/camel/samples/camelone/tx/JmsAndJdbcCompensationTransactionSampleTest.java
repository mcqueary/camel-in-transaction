package org.apache.cmueller.camel.samples.camelone.tx;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.junit4.CamelSpringTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

public class JmsAndJdbcCompensationTransactionSampleTest extends CamelSpringTestSupport {
	
	private JdbcTemplate jdbc;
	private TransactionTemplate transactionTemplate;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();

		DataSource ds = context.getRegistry().lookup("dataSource", DataSource.class);
		jdbc = new JdbcTemplate(ds);

		PlatformTransactionManager transactionManager = context.getRegistry().lookup("dataSourceTransactionManager", PlatformTransactionManager.class);
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
	public void moneyShouldTransfer() {
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));

		template.sendBodyAndHeader("tibjms:queue:transaction.incoming.one?timeToLive=3000", 100L, "JMS_TIBCO_PRESERVE_UNDELIVERED", true);

		Exchange exchange = consumer.receive("tibjms:queue:transaction.outgoing.one", 5000);
		assertNotNull(exchange);

		assertEquals(900, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1100, queryForLong("SELECT balance from account where name = 'bar'"));
	}

	@Test
	public void moneyShouldNotTransferWhenExceptionInBetweenUpdates() {
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));

		template.sendBodyAndHeader("tibjms:queue:transaction.incoming.two?timeToLive=3000", 100L, "JMS_TIBCO_PRESERVE_UNDELIVERED", true);

		Exchange exchange = consumer.receive("tibjms:queue:$sys.undelivered", 5000);
		assertNotNull(exchange);

		assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
	}

	@Test
	public void moneyShouldNotTransferWhenExceptionAfterUpdates() {
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));

		template.sendBodyAndHeader("tibjms:queue:transaction.incoming.three?timeToLive=3000", 100L, "JMS_TIBCO_PRESERVE_UNDELIVERED", true);

		Exchange exchange = consumer.receive("tibjms:queue:$sys.undelivered", 5000);
		assertNotNull(exchange);

		assertEquals(1000, queryForLong("SELECT balance from account where name = 'foo'"));
		assertEquals(1000, queryForLong("SELECT balance from account where name = 'bar'"));
	}
	
    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
            	context.setTracing(true);
            	
                from("tibjmsTx:queue:transaction.incoming.one")
                    .transacted("PROPAGATION_REQUIRED_JMS")
                    .transacted("PROPAGATION_REQUIRED_JDBC")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .to("tibjmsTx:queue:transaction.outgoing.one");
                
                from("tibjmsTx:queue:transaction.incoming.two")
                    .transacted("PROPAGATION_REQUIRED_JMS")
                    .transacted("PROPAGATION_REQUIRED_JDBC")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .throwException(new SQLException("forced exception for test"))
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .to("tibjmsTx:queue:transaction.outgoing.two");
                
                from("tibjmsTx:queue:transaction.incoming.three")
                    .transacted("PROPAGATION_REQUIRED_JMS")
                    .transacted("PROPAGATION_REQUIRED_JDBC")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'foo') - # WHERE name = 'foo'?dataSourceRef=dataSource")
                    .to("sql:UPDATE account SET balance = (SELECT balance from account where name = 'bar') + # WHERE name = 'bar'?dataSourceRef=dataSource")
                    .throwException(new SQLException("forced exception for test"))
                    .to("tibjmsTx:queue:transaction.outgoing.three");
            }
        };
    }

	@Override
	protected AbstractApplicationContext createApplicationContext() {
		return new ClassPathXmlApplicationContext("META-INF/spring/JmsAndJdbcCompensationTransactionSampleTest-context.xml");
	}
}