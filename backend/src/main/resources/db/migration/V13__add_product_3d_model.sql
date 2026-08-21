-- A product's 3D model, for the detail screen's "View in 3D".
--
-- NULLABLE AND EMPTY, and that is the point rather than a starting state to
-- be backfilled away. Almost no grocery product will ever have a 3D model -
-- photographing a bag of atta is cheap, modelling one is not - so the column
-- exists to let the few products that justify one carry it. The UI shows the
-- 3D affordance only when this is non-null, so a catalogue where every row
-- is NULL behaves exactly as it does today.
--
-- ON products, NOT product_variants, matching product_images (see V11): the
-- gallery belongs to the product, and a 1kg and a 5kg pack of the same atta
-- are the same shape.
--
-- VARCHAR(500) matches product_images.image_url - these are URLs to an
-- external asset host, never the model itself. A GLB in a database column
-- would be megabytes per row on a Supabase instance sized for text.
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS model_3d_url VARCHAR(500);
