// Game Application
const app = Vue.createApp({
    data() {
        return {
            // Connection and player info
            connected: false,
            stompClient: null,
            gameId: '',
            playerName: '',
            playerId: '',

            // Game state
            gamePhase: '',
            players: [],
            currentPlayerIndex: 0,
            bidHistory: [],
            winningBid: 0,
            callerId: null,
            trumpSuit: null,
            friendCards: [],
            currentTrick: { plays: [] },
            completedTricks: 0,
            callerTeamPoints: 0,
            opponentTeamPoints: 0,

            // Player's cards
            hand: [],

            // UI controls
            bidAmount: 150,
            minBid: 150,
            selectedFriendCards: [],

            // Game logs
            gameLogs: [],
            toastMessage: '',

            // Constants
            suits: ['HEARTS', 'DIAMONDS', 'CLUBS', 'SPADES'],
            ranks: ['ACE', 'KING', 'QUEEN', 'JACK', 'TEN', 'NINE', 'EIGHT', 'SEVEN', 'SIX', 'FIVE']
        };
    },
    computed: {
        isMyTurn() {
            if (!this.players || this.players.length === 0) return false;
            const currentPlayer = this.players[this.currentPlayerIndex];
            return currentPlayer && currentPlayer.id === this.playerId;
        },
        currentPlayerId() {
            if (!this.players || this.players.length === 0) return null;
            const currentPlayer = this.players[this.currentPlayerIndex];
            return currentPlayer ? currentPlayer.id : null;
        }
    },
    methods: {
        // Connection methods
        createGame() {
            fetch('/api/games/create', {
                method: 'POST'
            })
            .then(response => response.json())
            .then(data => {
                this.gameId = data.gameId;
                this.log(`Game created with ID: ${this.gameId}`);
            })
            .catch(error => {
                console.error('Error creating game:', error);
                this.showToast('Failed to create game. Please try again.');
            });
        },

        joinGame() {
            if (!this.gameId || !this.playerName) {
                this.showToast('Please enter Game ID and your name.');
                return;
            }

            this.connect();
        },

        connect() {
            const socket = new SockJS('/ws');
            this.stompClient = Stomp.over(socket);

            // Enable debug logging to see all STOMP messages
            this.stompClient.debug = function(msg) {
                console.log("STOMP DEBUG:", msg);
            };

            this.stompClient.connect({}, frame => {
                this.connected = true;
                this.log(`Connected to game server`);
                console.log("Connected with frame:", frame);

                // Extract user ID from connection
                const sessionId = /user-([^,]+)/.exec(frame);
                if (sessionId) {
                    console.log("Detected session ID:", sessionId[1]);
                }

                // Subscribe to game events
                this.stompClient.subscribe(`/topic/game/${this.gameId}`, this.onGameMessage);

                // Subscribe to user-specific queues with explicit headers
                this.stompClient.subscribe('/user/queue/game', this.onPrivateGameMessage, {id: 'private-game-sub'});
                this.stompClient.subscribe('/user/queue/hand', this.onHandUpdate, {id: 'hand-updates-sub'});

                console.log("Subscriptions established");

                // Join the game
                this.stompClient.send("/app/game/join", {}, JSON.stringify({
                    type: 'JoinGame',
                    gameId: this.gameId,
                    playerName: this.playerName
                }));
            }, error => {
                console.error('STOMP connection error:', error);
                this.connected = false;
                this.showToast('Failed to connect to game server. Please try again.');
            });
        },

        disconnect() {
            if (this.stompClient) {
                this.stompClient.disconnect();
            }
            this.connected = false;
            this.log('Disconnected from game server');
        },

        // Message handlers
        onGameMessage(message) {
            try {
                const data = JSON.parse(message.body);
                console.log("Game message received:", data.type);

                // Special handling for HandUpdate messages
                if (data.type === 'HandUpdate') {
                    console.log(`Hand update received via game topic. For player: ${data.playerId}, cards: ${data.hand.length}`);
                    console.log(`My player ID: ${this.playerId}`);

                    // Set the playerId if we're receiving first game messages and don't have it yet
                    if (!this.playerId && data.playerId) {
                        // Find if this is the first message with our player name
                        const player = this.players.find(p => p.name === this.playerName);
                        if (player && player.id === data.playerId) {
                            console.log(`Setting my player ID to ${data.playerId} based on name match`);
                            this.playerId = data.playerId;
                        }
                    }

                    // Check if this update is intended for this player
                    if (data.playerId === this.playerId) {
                        console.log("This hand update is for me - processing cards");
                        this.hand = data.hand;
                        this.log(`Received ${this.hand.length} cards`);
                        this.showToast(`Successfully received ${this.hand.length} cards`);
                    } else {
                        console.log(`This hand update is not for me (${this.playerId}) - ignoring`);
                    }
                }

                // Always process the message normally after special handling
                this.processMessage(data);
            } catch (e) {
                console.error("Error processing game message:", e, message);
            }
        },

        onPrivateGameMessage(message) {
            const data = JSON.parse(message.body);
            this.processMessage(data);
        },

        onHandUpdate(message) {
            console.log("Received hand update message:", message);
            try {
                const data = JSON.parse(message.body);
                console.log("Hand update data:", data);
                if (data.type === 'HandUpdate') {
                    console.log(`Received ${data.hand.length} cards in hand update`);
                    this.hand = data.hand;
                    this.log(`Received ${this.hand.length} cards`);
                    this.showToast(`Successfully received ${this.hand.length} cards`);
                }
            } catch (e) {
                console.error("Error processing hand update:", e);
                this.showToast("Error processing card data");
            }
        },

        processMessage(data) {
            switch (data.type) {
                case 'GameJoined':
                    this.playerId = data.playerId;
                    this.log(`Joined game as ${data.playerName} (${data.playerId})`);
                    break;

                case 'PlayerJoined':
                    this.log(`Player ${data.playerName} joined (${data.playerCount} players total)`);
                    break;

                case 'HandUpdate':
                    // Process hand updates that come through the game topic (broadcast)
                    // Only apply the update if it's for the current player or if no player ID is specified
                    if (!data.playerId || data.playerId === this.playerId) {
                        console.log(`Received ${data.hand.length} cards via broadcast channel`);
                        this.hand = data.hand;
                        this.log(`Received ${this.hand.length} cards`);
                        this.showToast(`Successfully received ${this.hand.length} cards`);
                    }
                    break;

                case 'GameStateUpdate':
                    this.updateGameState(data.gameState);
                    break;

                case 'BidPlaced':
                    const bidMsg = data.bid !== null
                        ? `Player ${this.getPlayerName(data.playerId)} bid ${data.bid}`
                        : `Player ${this.getPlayerName(data.playerId)} passed`;
                    this.log(bidMsg);
                    break;

                case 'TrickComplete':
                    this.log(`Trick won by ${this.getPlayerName(data.winnerId)} with ${data.points} points`);
                    break;

                case 'GameComplete':
                    const winnerTeam = data.winner === 'CALLER' ? 'Caller Team' : 'Opponent Team';
                    this.log(`Game over! ${winnerTeam} wins with ${data.winner === 'CALLER' ? data.callerPoints : data.opponentPoints} points`);
                    this.showToast(`Game over! ${winnerTeam} wins!`);
                    break;

                case 'Error':
                    console.error('Game error:', data.message);
                    this.showToast(`Error: ${data.message}`);
                    break;
            }
        },

        updateGameState(gameState) {
            const previousPhase = this.gamePhase;
            this.gamePhase = gameState.phase;
            this.players = gameState.players;
            this.currentPlayerIndex = gameState.currentPlayerIndex;
            this.bidHistory = gameState.bidHistory;
            this.winningBid = gameState.winningBid;
            this.callerId = gameState.callerId;
            this.trumpSuit = gameState.trumpSuit;
            this.friendCards = gameState.friendCards;
            this.currentTrick = gameState.currentTrick;
            this.completedTricks = gameState.completedTricks;
            this.callerTeamPoints = gameState.callerTeamPoints;
            this.opponentTeamPoints = gameState.opponentTeamPoints;

            // Log phase transition for debugging
            if (previousPhase !== this.gamePhase) {
                console.log(`Game phase changed from ${previousPhase || 'INITIAL'} to ${this.gamePhase}`);
                if (this.gamePhase === 'BIDDING') {
                    console.log(`Current hand: ${JSON.stringify(this.hand)}`);
                    // Request hand update if hand is empty
                    if (this.hand.length === 0) {
                        this.requestHandUpdate();
                    }
                }
            }

            // Update min bid based on current highest bid
            if (this.bidHistory && this.bidHistory.length > 0) {
                const highestBid = Math.max(...this.bidHistory.filter(b => b.bid !== null).map(b => b.bid), 0);
                this.minBid = this.getNextBidAmount(highestBid);
            }

            // Log phase changes
            if (this.isMyTurn) {
                if (gameState.phase === 'BIDDING') {
                    this.showToast("It's your turn to bid");
                } else if (gameState.phase === 'FRIEND_SELECTION' && this.playerId === this.callerId) {
                    this.showToast("Select trump suit and friend cards");
                } else if (gameState.phase === 'TRICK_PLAYING') {
                    this.showToast("It's your turn to play a card");
                }
            }
        },

        // Game actions
        requestHandUpdate() {
            if (this.stompClient && this.connected) {
                console.log("Requesting hand update from server");
                this.stompClient.send("/app/game/hand", {}, JSON.stringify({
                    type: 'RequestHand',
                    gameId: this.gameId
                }));
                this.log("Requesting cards from server...");

                // Add a visual indicator that cards are being requested
                this.showToast("Requesting cards from server...");

                // Set a timer to check if cards were received
                setTimeout(() => {
                    if (this.hand.length === 0) {
                        console.log("Still no cards received after request");
                        this.showToast("No cards received. Try again or refresh the page.");
                    }
                }, 3000);
            } else {
                console.error("Cannot request cards: not connected");
                this.showToast("You are not connected to the game server. Please refresh the page and try again.");
            }
        },

        placeBid(bid) {
            if (!this.isMyTurn) return;

            this.stompClient.send("/app/game/bid", {}, JSON.stringify({
                type: 'PlaceBid',
                bid: bid
            }));

            const actionMsg = bid !== null ? `You bid ${bid}` : 'You passed';
            this.log(actionMsg);
        },

        selectTrump(suit) {
            if (this.playerId !== this.callerId) return;

            this.stompClient.send("/app/game/trump", {}, JSON.stringify({
                type: 'SelectTrump',
                trumpSuit: suit
            }));

            this.log(`You selected ${suit} as trump`);
        },

        toggleFriendCard(suit, rank) {
            if (this.playerId !== this.callerId) return;
            if (this.isFriendCardInvalid(suit, rank)) return;

            const cardId = `${suit}_${rank}`;
            const index = this.selectedFriendCards.findIndex(c => c.id === cardId);

            if (index === -1) {
                // Add card if not already selected and we have less than 2
                if (this.selectedFriendCards.length < 2) {
                    this.selectedFriendCards.push({
                        suit: suit,
                        rank: rank,
                        id: cardId
                    });
                }
            } else {
                // Remove card if already selected
                this.selectedFriendCards.splice(index, 1);
            }
        },

        isFriendCardSelected(suit, rank) {
            const cardId = `${suit}_${rank}`;
            return this.selectedFriendCards.some(c => c.id === cardId);
        },

        isFriendCardInvalid(suit, rank) {
            // Cannot select Ace of Spades
            return suit === 'SPADES' && rank === 'ACE';
        },

        selectFriendCards() {
            if (this.playerId !== this.callerId || this.selectedFriendCards.length !== 2) return;

            this.stompClient.send("/app/game/friends", {}, JSON.stringify({
                type: 'SelectFriendCards',
                friendCards: this.selectedFriendCards
            }));

            const card1 = this.selectedFriendCards[0];
            const card2 = this.selectedFriendCards[1];
            this.log(`You selected ${card1.suit} ${card1.rank} and ${card2.suit} ${card2.rank} as friend cards`);
        },

        playCard(card) {
            if (!this.isMyTurn || this.gamePhase !== 'TRICK_PLAYING') return;

            this.stompClient.send("/app/game/play", {}, JSON.stringify({
                type: 'PlayCard',
                card: card
            }));

            this.log(`You played ${card.suit} ${card.rank}`);
        },

        // Helper functions
        getPlayerName(playerId) {
            const player = this.players.find(p => p.id === playerId);
            return player ? player.name : 'Unknown Player';
        },

        getNextBidAmount(currentBid) {
            if (currentBid < 150) return 150;
            if (currentBid < 200) return currentBid + 5;
            if (currentBid < 250) return currentBid + 10;
            return 250;
        },

        getCardDisplay(card) {
            return `${card.rank} of ${card.suit}`;
        },

        getCardClass(card) {
            const suitClass = card.suit.toLowerCase();
            const isTrump = card.suit === this.trumpSuit ? 'trump' : '';
            return `${suitClass} ${isTrump}`;
        },

        getSuitClass(suit) {
            return suit.toLowerCase();
        },

        log(message) {
            this.gameLogs.unshift(`${new Date().toLocaleTimeString()}: ${message}`);
            // Keep log size reasonable
            if (this.gameLogs.length > 100) {
                this.gameLogs.pop();
            }
        },

        showToast(message) {
            this.toastMessage = message;
            const toastEl = this.$refs.toast;
            const toast = new bootstrap.Toast(toastEl);
            toast.show();
        }
    }
});

app.mount('#app');
