package _game.try1

import Services.GameManagerService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class GameCleanupScheduler(
    private val gameManagerService: GameManagerService
) {

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    fun cleanupInactiveGames() {
        gameManagerService.cleanupInactiveGames()
    }
}