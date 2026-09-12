package com.gpstore.order.cancellation;

import com.gpstore.platform.CustomerOwnedRead;
import com.gpstore.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a customer owes a shop for cancelling, on both sides of the counter.
 *
 * <p>THE CUSTOMER'S VIEW IS THE POINT OF THE SCREEN. §11 lets a cancellation
 * charge follow somebody into a future order, which is only fair if they can
 * see it coming - a debt that is invisible until it appears on an unrelated
 * bill is indistinguishable from an overcharge.
 *
 * <p>THE CUSTOMER'S LIST SPANS SHOPS; THE SHOP'S LIST DOES NOT. One account
 * buys from several kiranas (§5), so "what do I owe" is a question about all
 * of them - read through CustomerOwnedRead, which widens the merchant
 * boundary for a caller who is a party to every row. The shop's list is the
 * mirror image and stays narrow: a shop seeing what a customer owes its
 * competitor is a competitive leak, not an operational need.
 */
@RestController
@RequestMapping("/api/cancellation-dues")
public class CancellationDuesController {

    private final CancellationDues dues;
    private final CurrentUser currentUser;
    private final CustomerOwnedRead customerOwnedRead;
    private final CustomerCancellationDuesRepository repository;

    public CancellationDuesController(
            CancellationDues dues,
            CurrentUser currentUser,
            CustomerOwnedRead customerOwnedRead,
            CustomerCancellationDuesRepository repository) {
        this.dues = dues;
        this.currentUser = currentUser;
        this.customerOwnedRead = customerOwnedRead;
        this.repository = repository;
    }

    /** Everything the signed-in customer owes, or has owed, anywhere. */
    @GetMapping("/mine")
    public MyDues mine() {
        Long me = currentUser.customerId();
        List<CustomerCancellationDue> all = customerOwnedRead.acrossShops(
                () -> dues.historyFor(me));
        BigDecimal outstanding = all.stream()
                .filter(CustomerCancellationDue::isOutstanding)
                .map(CustomerCancellationDue::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MyDues(outstanding, all.stream().map(CancellationDueView::from).toList());
    }

    /** What THIS shop is still owed. Admin only - see SecurityConfig. */
    @GetMapping("/outstanding")
    public List<CancellationDueView> outstandingForShop() {
        return repository.findByStatusOrderByCreatedAtAsc(DueStatus.OUTSTANDING)
                .stream().map(CancellationDueView::from).toList();
    }

    /** The shop writes one off. Admin only - see SecurityConfig. */
    @PostMapping("/{dueId}/waive")
    public CancellationDueView waive(@PathVariable Long dueId) {
        return CancellationDueView.from(dues.waive(dueId, "admin:" + currentUser.customerId()));
    }

    public record MyDues(BigDecimal totalOutstanding, List<CancellationDueView> dues) {}
}
