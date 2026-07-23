package io.lemadane.recordmaster.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DemoApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void testCustomerWithOrderSuccess() {
        Map<String, Object> customer = Map.of(
            "email", "maria@example.com",
            "name", "Maria Santos"
        );
        Map<String, Object> order = Map.of(
            "total", 1500.00
        );
        Map<String, Object> body = Map.of(
            "customer", customer,
            "order", order
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/transactions/customer-with-order",
            body,
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> respBody = response.getBody();
        assertNotNull(respBody);
        Map<?, ?> respCustomer = (Map<?, ?>) respBody.get("customer");
        Map<?, ?> respOrder = (Map<?, ?>) respBody.get("order");
        assertEquals("maria@example.com", respCustomer.get("email"));
        assertEquals(1500.00, ((Number) respOrder.get("total")).doubleValue());
    }

    @Test
    public void testCustomerWithOrderFailureRollback() {
        Map<String, Object> customer = Map.of(
            "email", "invalid@example.com",
            "name", "Invalid Customer"
        );
        Map<String, Object> order = Map.of(
            "total", -100.00
        );
        Map<String, Object> body = Map.of(
            "customer", customer,
            "order", order
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/transactions/customer-with-order",
            body,
            Map.class
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> respBody = response.getBody();
        assertNotNull(respBody);
        assertEquals(true, respBody.get("rolledBack"));
    }

    @Test
    public void testRollbackDemo() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/transactions/rollback-demo",
            null,
            Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> respBody = response.getBody();
        assertNotNull(respBody);
        assertEquals("Transaction rolled back successfully", respBody.get("message"));
        assertEquals(false, respBody.get("customerPresentAfterward"));
    }
}
