package org.apache.cmueller.camel.samples.camelone.jms;

import java.sql.SQLException;
import java.util.HashMap;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.junit4.CamelSpringTestSupport;
import org.junit.Test;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class JmsTransactionSampleTest extends CamelSpringTestSupport {
    
    @Test
    public void moneyShouldBeTransferred() {
    	HashMap<String, Object> headers = new HashMap<String,Object>();
    	headers.put("JMS_TIBCO_PRESERVE_UNDELIVERED", true);
    	headers.put("amount", "100");
        template.sendBodyAndHeaders("tibjms:queue:transaction.incoming.one", "Camel rocks!", headers);
        
        Exchange exchange = consumer.receive("tibjms:queue:transaction.outgoing.one", 5000);
        assertNotNull(exchange);
    }
    
    @Test
    public void moneyShouldNotBeTransferred() {
    	HashMap<String, Object> headers = new HashMap<String,Object>();
    	headers.put("JMS_TIBCO_PRESERVE_UNDELIVERED", true);
    	headers.put("amount", "100");
    	template.sendBodyAndHeaders("tibjms:queue:transaction.incoming.two?timeToLive=3000", "Camel rocks!", headers);
        
        Exchange exchange = consumer.receive("tibjms:queue:$sys.undelivered", 5000);
        assertNotNull(exchange);
    }
    
    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("tibjmsTx:queue:transaction.incoming.one")
                    .transacted("PROPAGATION_REQUIRED")
                    .to("bean:businessService?method=computeOffer")
                    .to("tibjmsTx:queue:transaction.outgoing.one");
                
                from("tibjmsTx:queue:transaction.incoming.two")
                    .transacted("PROPAGATION_REQUIRED")
                    .throwException(new SQLException("forced exception for test"))
                    .to("tibjmsTx:queue:transaction.outgoing.two");
            }
        };
    }
    
    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("META-INF/spring/JmsTransactionSampleTest-context.xml");
    }
}