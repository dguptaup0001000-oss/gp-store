-- One customer saving one catalogue product is one fact. Older clients could
-- retry the POST and leave duplicate rows, so retain the oldest row before
-- enforcing that fact at the database boundary.
DELETE FROM wishlist
 WHERE customer_id IS NOT NULL
   AND product_id IS NOT NULL
   AND id NOT IN (
       SELECT keep_id
         FROM (
              SELECT MIN(id) AS keep_id
                FROM wishlist
               WHERE customer_id IS NOT NULL AND product_id IS NOT NULL
               GROUP BY customer_id, product_id
         ) kept
 );

CREATE UNIQUE INDEX IF NOT EXISTS uk_wishlist_customer_product
    ON wishlist (customer_id, product_id);
