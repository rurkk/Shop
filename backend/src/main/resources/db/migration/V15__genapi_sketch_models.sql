UPDATE sketch_model_settings
SET version = version + 1,
    response_model = 'gemini-2-5-flash-lite',
    image_model = 'flash'
WHERE singleton;
