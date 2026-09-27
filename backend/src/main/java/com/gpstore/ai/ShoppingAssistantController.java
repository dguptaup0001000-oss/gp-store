package com.gpstore.ai;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplace/assistant")
public class ShoppingAssistantController {
    private final ShoppingAssistantService assistant;

    public ShoppingAssistantController(ShoppingAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping
    public ShoppingAssistantService.Answer answer(
            @RequestBody ShoppingAssistantService.Request request) {
        return assistant.answer(request);
    }
}
