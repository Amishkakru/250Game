package _game.try1

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = ["_game.try1", "Controller", "Model", "Services"])
class Try1Application

fun main(args: Array<String>) {
	runApplication<Try1Application>(*args)
}
