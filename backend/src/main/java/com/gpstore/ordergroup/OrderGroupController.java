package com.gpstore.ordergroup;

import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A customer's own checkouts, each of which may be several shops' orders.
 *
 * EVERY READ HERE SPANS SHOPS, and every read here is keyed on the customer id
 * from the token. Those two facts belong together: the tenant filter keeps
 * merchants apart, and a checkout deliberately crosses that line, so what
 * keeps customers apart has to be the ownership check - which is why
 * CustomerOwnedRead wraps the call and OrderGroupService compares the id
 * inside it.
 *
 * NO GROUP ID IS TRUSTED. A guessed id belonging to somebody else answers
 * "not found", not "forbidden" - a 403 on a guessed id confirms the checkout
 * exists, which is the whole prize in walking a range of them.
 */
@RestController
@RequestMapping("/api/orders/groups")
public class OrderGroupController {

    private final OrderGroupService groups;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;

    public OrderGroupController(OrderGroupService groups, CurrentUser currentUser,
                                CustomerOwnedRead customerOwnedRead) {
        this.groups = groups;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
    }

    /** Every checkout this customer has made, newest first. */
    @GetMapping
    public List<OrderGroupService.GroupView> myCheckouts() {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(() -> groups.myCheckouts(me));
    }

    /** One checkout, with what each shop is doing about its half. */
    @GetMapping("/{groupId}")
    public OrderGroupService.GroupView myCheckout(@PathVariable Long groupId) {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(() -> groups.myCheckout(me, groupId));
    }

    /**
     * Cancels as much of a checkout as can still be cancelled.
     *
     * ANSWERS PER SHOP because the outcome genuinely is per shop - one kirana
     * may still be able to stop while the other's rider is at the door. The
     * response says which did what rather than a single success or failure
     * that would be a lie either way.
     */
    @PutMapping("/{groupId}/cancel")
    public OrderGroupService.CancelResult cancelWholeCheckout(@PathVariable Long groupId) {
        Long me = currentUser.customerId();
        return customerOwnedRead.acrossShops(() -> groups.cancelWholeCheckout(me, groupId));
    }
}
