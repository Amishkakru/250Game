package Controller

import Model.GameStateDTO
import Services.GameManagerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/games")
class GameController(
    private val gameManagerService: GameManagerService
) {

    @PostMapping("/create")
    fun createGame(): ResponseEntity<Map<String, String>> {
        val gameId = gameManagerService.createGame()
        return ResponseEntity.ok(mapOf("gameId" to gameId))
    }

    @GetMapping("/{gameId}")
    fun getGameInfo(@PathVariable gameId: String): ResponseEntity<GameStateDTO> {
        val game = gameManagerService.getGame(gameId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(game.toDTO())
    }

    @GetMapping("/active")
    fun getActiveGames(): ResponseEntity<List<Map<String, Any>>> {
        // This would return public game info for lobby/matchmaking
        // Implementation depends on your requirements
        return ResponseEntity.ok(emptyList())
    }
}