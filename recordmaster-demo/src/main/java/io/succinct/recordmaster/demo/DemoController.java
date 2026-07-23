package io.succinct.recordmaster.demo;

import io.succinct.recordmaster.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/transactions")
public class DemoController {

    private final RecordDatabase db;

    public DemoController(RecordDatabase db) {
        this.db = db;
    }

    public record CustomerRequest(String email, String name) {}
    public record OrderRequest(Double total) {}
    public record CustomerWithOrderRequest(CustomerRequest customer, OrderRequest order) {}
    public record CustomerWithOrderResponse(Customer customer, Order order) {}

    @PostMapping("/customer-with-order")
    public ResponseEntity<?> createCustomerWithOrder(@RequestBody CustomerWithOrderRequest request) {
        try {
            CustomerWithOrderResponse response = db.transaction(tx -> {
                RecordTable<UUID, Customer> customerTable = tx.table(Customer.class);
                RecordTable<UUID, Order> orderTable = tx.table(Order.class);

                Customer customer = new Customer(
                    UUID.randomUUID(),
                    request.customer().email(),
                    request.customer().name(),
                    CustomerStatus.ACTIVE,
                    Instant.now()
                );
                
                if (request.order().total() < 0) {
                    throw new IllegalArgumentException("Order total cannot be negative");
                }

                Order order = new Order(
                    UUID.randomUUID(),
                    request.order().total()
                );

                customerTable.insert(customer);
                orderTable.insert(order);

                return new CustomerWithOrderResponse(customer, order);
            });

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "rolledBack", true));
        } catch (DuplicateIndexValueException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "rolledBack", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage(), "rolledBack", true));
        }
    }

    @PostMapping("/rollback-demo")
    public ResponseEntity<?> rollbackDemo() {
        UUID id = UUID.randomUUID();
        try {
            db.transaction(tx -> {
                RecordTable<UUID, Customer> table = tx.table(Customer.class);
                Customer temp = new Customer(
                    id,
                    "temp-" + id + "@example.com",
                    "Temp Demo Customer",
                    CustomerStatus.ACTIVE,
                    Instant.now()
                );
                table.insert(temp);
                
                tx.setRollbackOnly("Rollback demonstration");
            });
            return ResponseEntity.ok(Map.of("message", "Unexpected success"));
        } catch (TransactionRolledBackException e) {
            boolean present = db.table(Customer.class).findById(id).isPresent();
            return ResponseEntity.ok(Map.of(
                "message", "Transaction rolled back successfully",
                "reason", e.rollbackReason().orElse("None"),
                "customerPresentAfterward", present
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
