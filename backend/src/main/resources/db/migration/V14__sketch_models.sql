CREATE TABLE sketch_model_settings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    response_model text NOT NULL,
    image_model text NOT NULL
);

INSERT INTO sketch_model_settings (response_model, image_model)
VALUES ('gpt-5.5', 'gpt-image-2.5-flare');
