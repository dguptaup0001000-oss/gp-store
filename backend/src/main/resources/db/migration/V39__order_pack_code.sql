-- A code a worker can TYPE when the camera will not scan.
--
-- WHY NOT JUST LET THEM TYPE THE ORDER NUMBER. That was the obvious answer
-- and it is the wrong one. Order numbers are sequential (GP20260902000116),
-- printed on the invoice, shown to the customer and readable off any
-- delivered order. If typing one claimed an order, any worker could claim
-- orders they had never touched, and the whole point of the label - that
-- holding it proves you have the carton in your hands - would be gone.
--
-- So this is a SECOND CREDENTIAL FOR THE SAME LABEL, not a way around it:
-- random like the QR token, printed next to it, and consumed by the same
-- single-use flag. What changes is only that a human can read it out.
--
-- SHORT ENOUGH TO TYPE ON A CRACKED PHONE, at eight characters from an
-- alphabet with no O/0 and no I/1/L, which are the pairs people actually
-- confuse when reading a smudged sticker in bad light. Thirty symbols to
-- the eighth is about 6.6e11 combinations - and the code entry path counts
-- and refuses repeated wrong guesses, so the search space is never walked.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS pack_code VARCHAR(16);

-- One code, once, while it is live. Partial so it only indexes the handful
-- of orders that currently have a printed label, and so the column can stay
-- NULL on every historical order without a backfill that would invent codes
-- for labels nobody ever printed.
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_pack_code
    ON orders (pack_code)
    WHERE pack_code IS NOT NULL;
