-- Hindi/Hinglish grocery vocabulary for Smart Search.
--
-- WHY THIS TABLE HAS TO EXIST. Every other layer of Smart Search is
-- algorithmic: SearchNormalizer's phonetic key already collapses spelling
-- variants and typos (chawal/chaval/chaawal, sogar/sugar, haldi/haldhi) with
-- no data at all. But no string algorithm gets from "chini" to "sugar" -
-- that is a TRANSLATION. It needs a dictionary.
--
-- WHAT KEEPS IT SMALL. One row per CONCEPT, not per spelling. The phonetic
-- key generates the variants: "chini" is stored once and "cheeni", "chinee"
-- and "chiny" all reach it. That is why this is ~90 rows rather than the
-- thousands of hand-written variants the design deliberately avoids.
--
-- It is also DATA, not code: a shop that sells something regional adds a row
-- and Smart Search picks it up on the next cache refresh, with no deploy.
CREATE TABLE IF NOT EXISTS search_synonyms (
    id             BIGSERIAL PRIMARY KEY,
    -- What a customer types. Stored as written so the table stays readable
    -- and editable by a person; the phonetic key is derived in Java at load
    -- time rather than stored, so a change to the phonetic rules does not
    -- need a data migration.
    term           VARCHAR(64)  NOT NULL,
    -- What to search the catalogue for instead. Must be a word that actually
    -- appears in product names or brands.
    canonical_term VARCHAR(64)  NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_search_synonym_term UNIQUE (term)
);

CREATE INDEX IF NOT EXISTS idx_search_synonyms_active ON search_synonyms (active);

INSERT INTO search_synonyms (term, canonical_term) VALUES
    -- Staples
    ('chini', 'sugar'),
    ('shakkar', 'sugar'),
    ('namak', 'salt'),
    ('chawal', 'rice'),
    ('atta', 'flour'),
    ('maida', 'flour'),
    ('suji', 'semolina'),
    ('rava', 'semolina'),
    ('besan', 'gram flour'),
    ('daal', 'dal'),
    ('chana', 'chickpea'),
    ('rajma', 'kidney beans'),
    ('moong', 'mung'),
    ('masoor', 'lentil'),
    ('toor', 'pigeon pea'),
    ('arhar', 'pigeon pea'),
    ('urad', 'black gram'),
    ('poha', 'flattened rice'),
    ('sabudana', 'sago'),
    -- Dairy
    ('doodh', 'milk'),
    ('dahi', 'curd'),
    ('paneer', 'cottage cheese'),
    ('makhan', 'butter'),
    ('ghee', 'ghee'),
    ('malai', 'cream'),
    ('lassi', 'lassi'),
    ('chaas', 'buttermilk'),
    -- Oils and fats
    ('tel', 'oil'),
    ('sarson', 'mustard'),
    ('til', 'sesame'),
    ('nariyal', 'coconut'),
    ('moongphali', 'groundnut'),
    ('badam', 'almond'),
    ('kaju', 'cashew'),
    ('kishmish', 'raisin'),
    ('akhrot', 'walnut'),
    ('pista', 'pistachio'),
    -- Spices
    ('haldi', 'turmeric'),
    ('mirch', 'chilli'),
    ('lal mirch', 'red chilli'),
    ('kali mirch', 'black pepper'),
    ('jeera', 'cumin'),
    ('dhania', 'coriander'),
    ('saunf', 'fennel'),
    ('methi', 'fenugreek'),
    ('elaichi', 'cardamom'),
    ('laung', 'clove'),
    ('dalchini', 'cinnamon'),
    ('tej patta', 'bay leaf'),
    ('hing', 'asafoetida'),
    ('ajwain', 'carom'),
    ('kalonji', 'nigella'),
    ('imli', 'tamarind'),
    ('masala', 'masala'),
    -- Vegetables
    ('aloo', 'potato'),
    ('pyaz', 'onion'),
    ('pyaaz', 'onion'),
    ('tamatar', 'tomato'),
    ('lehsun', 'garlic'),
    ('adrak', 'ginger'),
    ('gajar', 'carrot'),
    ('matar', 'peas'),
    ('gobi', 'cauliflower'),
    ('bhindi', 'okra'),
    ('baingan', 'brinjal'),
    ('palak', 'spinach'),
    ('nimbu', 'lemon'),
    ('kheera', 'cucumber'),
    ('shimla mirch', 'capsicum'),
    ('mooli', 'radish'),
    ('kaddu', 'pumpkin'),
    ('dhaniya patta', 'coriander leaves'),
    -- Fruit
    ('kela', 'banana'),
    ('seb', 'apple'),
    ('aam', 'mango'),
    ('angoor', 'grapes'),
    ('anar', 'pomegranate'),
    ('santra', 'orange'),
    ('papita', 'papaya'),
    ('tarbooj', 'watermelon'),
    -- Bakery, packaged, beverages
    ('biscuit', 'biscuit'),
    ('namkeen', 'namkeen'),
    ('chai', 'tea'),
    ('patti', 'tea'),
    ('coffee', 'coffee'),
    ('anda', 'egg'),
    ('bread', 'bread'),
    ('sirka', 'vinegar'),
    ('shahad', 'honey'),
    ('murabba', 'preserve'),
    ('achar', 'pickle'),
    ('papad', 'papad'),
    -- Household and personal care
    ('sabun', 'soap'),
    ('saboon', 'soap'),
    ('shampoo', 'shampoo'),
    ('tel maalish', 'hair oil'),
    ('manjan', 'toothpaste'),
    ('brush', 'toothbrush'),
    ('jhadu', 'broom'),
    ('phenyl', 'floor cleaner'),
    ('detergent', 'detergent'),
    ('surf', 'detergent'),
    ('bartan', 'dishwash'),
    ('agarbatti', 'incense'),
    ('mombatti', 'candle')
ON CONFLICT (term) DO NOTHING;
