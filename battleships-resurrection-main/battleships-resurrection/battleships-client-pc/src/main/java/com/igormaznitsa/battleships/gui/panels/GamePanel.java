/*
 * 
 *
 *    This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 */

package com.igormaznitsa.battleships.gui.panels;

import com.igormaznitsa.battleships.gui.Animation;
import com.igormaznitsa.battleships.gui.InfoBanner;
import com.igormaznitsa.battleships.gui.ScaleFactor;
import com.igormaznitsa.battleships.gui.StartOptions;
import com.igormaznitsa.battleships.gui.sprite.*;
import com.igormaznitsa.battleships.opponent.BattleshipsPlayer;
import com.igormaznitsa.battleships.opponent.BsGameEvent;
import com.igormaznitsa.battleships.opponent.GameEventType;
import com.igormaznitsa.battleships.sound.Sound;
import com.igormaznitsa.battleships.utils.ImageCursor;

import javax.swing.Timer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.igormaznitsa.battleships.gui.Animation.*;
import static com.igormaznitsa.battleships.opponent.GameEventType.*;
import static com.igormaznitsa.battleships.utils.Utils.RND;
import static java.lang.Math.round;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class GamePanel extends BasePanel implements BattleshipsPlayer {

  public static final Duration INTER_FRAME_DELAY = Duration.ofMillis(70);
  private static final Logger LOGGER = Logger.getLogger(GamePanel.class.getName());
  private static final int TICKS_BEFORE_CONTROL_ACTION = 3;
  private static final int GAME_FIELD_CELL_WIDTH = 23;
  private static final int GAME_FIELD_CELL_HEIGHT = 23;
  private static final Ellipse2D FIRE_BUTTON_AREA = new Ellipse2D.Float(184, 16, 43, 43);
  private static final Rectangle ACTION_PANEL_AREA =
          new Rectangle(287, 119, GAME_FIELD_CELL_WIDTH * GameField.FIELD_EDGE,
                  GAME_FIELD_CELL_HEIGHT * GameField.FIELD_EDGE);
  private static final Point HORIZONS_SPLASH_COORDS = new Point(561, 32);
  private static final Point HORIZONS_EXPLOSION_COORDS = new Point(585, 36);
  private static final long ENV_SOUNDS_TICKS_BIRD_SOUND = 60;
  private static final long ENV_SOUNDS_TICKS_OTHER_SOUND = 100;
  private final BufferedImage background;
  private final Timer timer;
  private final GameField gameField;
  private final BlockingDeque<BsGameEvent> queueToMe = new LinkedBlockingDeque<>(256);
  private final BlockingDeque<BsGameEvent> queueToOpponent = new LinkedBlockingDeque<>(256);
  private final AtomicReference<Optional<BsGameEvent>> savedGameEvent =
          new AtomicReference<>(Optional.empty());
  private final AtomicReference<ShipType> lastFiringShipType = new AtomicReference<>();
  private Stage currentStage;
  private int stageStep;
  private ControlElement selectedControl;
  private int controlTicksCounter;
  private ControlElement prevControl;
  private Point lastPressedEmptyCell = null;
  private boolean pressedPlaceShipMouseButton = false;
  private DecorationSprite activeDecorationSprite = null;
  private FallingObjectSprite activeFallingObjectSprite = null;
  private OneTimeWaterEffectSprite fieldWaterEffect = null;
  private long envTicksBeforeBirdSound = ENV_SOUNDS_TICKS_BIRD_SOUND;
  private long envTicksBeforeOtherSound = ENV_SOUNDS_TICKS_OTHER_SOUND;
  private List<FieldSprite> animatedSpriteField = Collections.emptyList();

  public GamePanel(final StartOptions startOptions, final Optional<ScaleFactor> scaleFactor, final ImageCursor gameCursor) {
    super(startOptions, scaleFactor, gameCursor);

    this.gameField = new GameField();
    this.background = Animation.FON.getFrame(0);

    this.addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseDragged(final MouseEvent mouseEvent) {
        if (currentStage == Stage.PLACING && pressedPlaceShipMouseButton) {
          final Point preparedMousePoint = scaleFactor.map(sf -> sf.translatePoint(mouseEvent))
                  .orElse(mouseEvent.getPoint());
          if (lastPressedEmptyCell == null) {
            lastPressedEmptyCell = mouse2game(preparedMousePoint);
            final GameField.CellState cellState =
                    gameField.getState(lastPressedEmptyCell.x, lastPressedEmptyCell.y);
            if (cellState == GameField.CellState.EMPTY) {
              gameField.tryMakePlaceholder(lastPressedEmptyCell, lastPressedEmptyCell);
            } else {
              lastPressedEmptyCell = null;
            }
          } else {
            final Point cellUnderMouse = mouse2game(preparedMousePoint);
            gameField.clearPlaceholder();
            gameField.tryMakePlaceholder(lastPressedEmptyCell, cellUnderMouse);
            refreshUi();
          }
        }
      }
    });

    this.addMouseListener(new MouseAdapter() {

      @Override
      public void mouseReleased(final MouseEvent mouseEvent) {
        if (currentStage == Stage.PLACING) {
          if (lastPressedEmptyCell != null) {
            Sound.MOUSE_FREE.play();
          }
          gameField.fixPlaceholder();
          refreshUi();
        }
      }

      @Override
      public void mousePressed(final MouseEvent mouseEvent) {
        final Point preparedMousePoint =
                scaleFactor.map(sf -> sf.translatePoint(mouseEvent)).orElse(mouseEvent.getPoint());
        final ControlElement detectedControl = ControlElement.find(preparedMousePoint);
        switch (currentStage) {
          case PLACING: {
            if (detectedControl == ControlElement.AUTO || detectedControl == ControlElement.DONE) {
              lastPressedEmptyCell = null;
              if (detectedControl == ControlElement.AUTO && gameField.hasAnyFreeShip()) {
                doSelectControl(detectedControl);
              } else if (detectedControl == ControlElement.DONE && !gameField.hasAnyFreeShip()) {
                doSelectControl(detectedControl);
              } else {
                detectedControl.getWrongSound().play();
              }
            } else if (ACTION_PANEL_AREA.contains(preparedMousePoint)) {
              lastPressedEmptyCell = mouse2game(preparedMousePoint);
              final GameField.CellState cellState =
                      gameField.getState(lastPressedEmptyCell.x, lastPressedEmptyCell.y);
              if (mouseEvent.isPopupTrigger()) {
                // remove
                pressedPlaceShipMouseButton = false;
                if (cellState == GameField.CellState.SHIP) {
                  final List<Point> removedShipCells =
                          gameField.tryRemoveShipAt(
                                  new Point(lastPressedEmptyCell.x, lastPressedEmptyCell.y));
                  if (!removedShipCells.isEmpty()) {
                    gameField.ensureBanAroundShips();
                    gameField.increaseFreeShips(removedShipCells.size());
                  }
                }
                lastPressedEmptyCell = null;
              } else {
                lastPressedEmptyCell = mouse2game(preparedMousePoint);
                // place
                pressedPlaceShipMouseButton = true;
                if (cellState == GameField.CellState.EMPTY) {
                  gameField.tryMakePlaceholder(lastPressedEmptyCell, lastPressedEmptyCell);
                } else {
                  lastPressedEmptyCell = null;
                }
                Sound.MOUSE_CLICK.play();
              }
              refreshUi();
            }
          }
          break;
          case TARGET_SELECT: {
            if (ACTION_PANEL_AREA.contains(preparedMousePoint)) {
              final Point cell = mouse2game(preparedMousePoint);
              if (gameField.tryMarkAsTarget(cell)) {
                refreshUi();
              }
              Sound.MOUSE_CLICK.play();
            } else if (FIRE_BUTTON_AREA.contains(preparedMousePoint)) {
              Sound.ATTACK_HORN.play();
              if (gameField.hasTarget()) {
                initStage(Stage.PANEL_EXIT);
              }
            }
          }
          break;
          default: {
            // do nothing
          }
          break;
        }

        if (detectedControl == ControlElement.PAUSE || detectedControl == ControlElement.EXIT
                || detectedControl == ControlElement.NEUTRAL) {
          doSelectControl(detectedControl);
        }
      }
    });

    this.timer = new Timer((int) INTER_FRAME_DELAY.toMillis(), e -> {
      this.processEnvironmentSounds();
      this.onTimer();
    });
    this.timer.setRepeats(true);
  }

  public static Point findShipRenderPositionForCell(final int cellX, final int cellY) {
    final int deltaX = 34;
    final int deltaY = 19;
    final int leftX = -16;
    final int middleY = 257;

    final double baseX = leftX + cellX * deltaX;
    final double baseY = middleY - cellX * deltaY;

    return new Point((int) Math.round(baseX + cellY * deltaX),
            (int) Math.round(baseY + cellY * deltaY));
  }

  public boolean needsRepaintForMouse() {
    return false;
  }

  private void processEnvironmentSounds() {
    this.envTicksBeforeBirdSound--;
    this.envTicksBeforeOtherSound--;
    if (this.envTicksBeforeBirdSound <= 0) {
      Sound sound = null;
      switch (RND.nextInt(8)) {
        case 1: {
          sound = Sound.SEAGULL_01;
        }
        break;
        case 3: {
          sound = Sound.SEAGULL_02;
        }
        break;
        case 5: {
          sound = Sound.SEAGULL_03;
        }
        break;
        case 4:
        default:
          break;
      }
      if (sound != null && !sound.isPlaying()) {
        sound.play();
      }
      this.envTicksBeforeBirdSound = ENV_SOUNDS_TICKS_BIRD_SOUND;
    }
    if (this.envTicksBeforeOtherSound <= 0) {
      Sound sound = null;
      switch (RND.nextInt(40)) {
        case 12:
          if (this.currentStage == Stage.TARGET_SELECT ||
                  this.currentStage == Stage.ENEMY_FIRING_RESULT) {
            sound = Sound.MORSE2;
          }
          break;
        case 14:
          if (this.currentStage == Stage.FIRING_RESULT) {
            sound = Sound.MORSE;
          }
          break;
        default: {
          if (RND.nextInt(20) > 17) {
            sound = Sound.DECK_CREAK;
          }
        }
        break;
      }
      if (sound != null && !sound.isPlaying()) {
        sound.play();
      }
      this.envTicksBeforeOtherSound = ENV_SOUNDS_TICKS_OTHER_SOUND;
    }
  }

  // auxiliary method for test purposes
  @SuppressWarnings("unused")
  protected void fillEmptyCellsByFish() {
    IntStream.range(0, GameField.FIELD_EDGE * GameField.FIELD_EDGE)
            .mapToObj(c -> new Point(c % GameField.FIELD_EDGE, c / GameField.FIELD_EDGE))
            .filter(p -> this.findShipForCell(p.x, p.y).isEmpty())
            .forEach(p -> this.animatedSpriteField.add(new FishSprite(p)));
    Collections.sort(this.animatedSpriteField);
  }

  @Override
  public void pushGameEvent(final BsGameEvent event) {
    if (event != null && !this.queueToMe.offer(event)) {
      if (this.queueToMe.offer(event)) {
        LOGGER.info("queued: " + event);
      } else {
        LOGGER.severe("Can't place event into queue for long time: " + event + " queue.size=");
        this.queueToMe.offer(new BsGameEvent(EVENT_FAILURE, 0, 0));
      }
    }
  }

  @Override
  public Optional<BsGameEvent> pollGameEvent(final Duration duration) throws InterruptedException {
    return Optional
            .ofNullable(this.queueToOpponent.poll(duration.toMillis(), TimeUnit.MILLISECONDS));
  }

  private void renderActionPanel(final Graphics2D g, final int offsetX, final int offsetY,
                                 final GameField field, final boolean placementMode) {
    for (int x = 0; x < GameField.FIELD_EDGE; x++) {
      final int gx = offsetX + x * GAME_FIELD_CELL_WIDTH + 3;
      for (int y = 0; y < GameField.FIELD_EDGE; y++) {
        final int gy = offsetY + y * GAME_FIELD_CELL_HEIGHT + 3;
        if (placementMode) {
          switch (field.getState(x, y)) {
            case EMPTY: {
              // none
            }
            break;
            case PLACEHOLDER: {
              g.drawImage(Animation.ACT_MAP.getFrame(6), null, gx, gy);
            }
            break;
            case BANNED: {
              g.drawImage(Animation.ACT_MAP.getFrame(2), null, gx, gy);
            }
            break;
            case SHIP: {
              g.drawImage(Animation.ACT_MAP.getFrame(3), null, gx, gy);
            }
            break;
            default: {
              throw new IllegalStateException("Unexpected cell state in placement mode: " + field.getState(x, y));
            }
          }
        } else {
          switch (field.getState(x, y)) {
            case EMPTY: {
              // none
            }
            break;
            case TARGET: {
              g.drawImage(Animation.ACT_MAP.getFrame(8), null, gx, gy);
            }
            break;
            case BANNED: {
              g.drawImage(Animation.ACT_MAP.getFrame(2), null, gx, gy);
            }
            break;
            case HIT: {
              g.drawImage(Animation.ACT_MAP.getFrame(0), null, gx, gy);
            }
            break;
            case KILL: {
              g.drawImage(Animation.ACT_MAP.getFrame(5), null, gx, gy);
            }
            break;
            case MISS: {
              g.drawImage(Animation.ACT_MAP.getFrame(7), null, gx, gy);
            }
            break;
            default: {
              throw new IllegalStateException("Unexpected cell state in placement mode: " + field.getState(x, y));
            }
          }
        }
      }
    }
  }

  private Point mouse2game(final Point point) {
    final int x = point.x - ACTION_PANEL_AREA.x;
    final int y = point.y - ACTION_PANEL_AREA.y;
    return new Point(x / GAME_FIELD_CELL_WIDTH, y / GAME_FIELD_CELL_HEIGHT);
  }

  private void doSelectControl(final ControlElement control) {
    if (control != this.selectedControl) {
      this.prevControl = this.selectedControl;
      this.selectedControl = control;
      this.controlTicksCounter = TICKS_BEFORE_CONTROL_ACTION;
      if (control != ControlElement.NONE) {
        control.getOkSound().play();
      }
      this.refreshUi();
    }
  }

  private void fireEventToOpponent(final BsGameEvent event) {
    if (!this.queueToOpponent.offer(Objects.requireNonNull(event))) {
      throw new IllegalStateException(
              "Can't queue output game event: " + event + " (size=" + this.queueToOpponent.size() + ')');
    }
  }

  private Optional<BsGameEvent> findGameEventInQueue(final Set<GameEventType> expected) {
    BsGameEvent result = null;
    final Set<UUID> alreadyMet = new HashSet<>();
    while (!Thread.currentThread().isInterrupted()) {
      result = this.queueToMe.poll();
      if (result == null) {
        break;
      } else if (result.getType().isForced() || expected.contains(result.getType())) {
        break;
      } else {
        if (!this.queueToMe.offerLast(result)) {
          LOGGER.severe("can't place event back : " + result);
          this.queueToMe.offerFirst(new BsGameEvent(EVENT_FAILURE, 0, 0));
        }
        if (alreadyMet.contains(result.getUuid())) {
          result = null;
          break;
        } else {
          alreadyMet.add(result.getUuid());
          result = null;
        }
      }
    }
    return Optional.ofNullable(result);
  }

  private void initStage(final Stage stage) {
    LOGGER.info("game stage: " + stage);
    this.currentStage = stage;
    this.stageStep = 0;
    this.refreshUi();
    this.startSoundForStage(stage);
  }

  private void startSoundForStage(final Stage stage) {
    switch (stage) {
      case PLACEMENT_START: {
        Sound.MENU_SCREEN_IN.play();
      }
      break;
      case PANEL_EXIT:
      case PLACEMENT_END_ANIMATION: {
        Sound.MENU_SCREEN_OUT.play();
      }
      break;
      case PANEL_ENTER: {
        Sound.MENU_IN.play();
      }
      break;
    }
  }

  @Override
  public final BattleshipsPlayer startPlayer() {
    return this;
  }

  @Override
  public final void disposePlayer() {

  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public boolean isReadyForGame() {
    return true;
  }

  @Override
  public String getId() {
    return "battleships-main-game-panel";
  }

  @Override
  public boolean isRemote() {
    return false;
  }

  private void sendFireNotificationForTargetCell(final ShipType shipType) {
    final Point target = this.gameField.removeTarget()
            .orElseThrow(() -> new Error("Target must be presented"));
    this.fireEventToOpponent(new BsGameEvent(
            shipType == ShipType.AIR_CARRIER ? EVENT_SHOT_MAIN : EVENT_SHOT_REGULAR,
            target.x, target.y));
  }

  @Override
  protected void doStart() {
    this.selectedControl = ControlElement.NONE;
    this.initStage(Stage.PLACEMENT_START);
    this.gameField.reset();
    Sound.WAVES_LOOP.playRepeat();
    this.timer.start();
    this.startPlayer();
  }

  private ShipType activateShipFire() {
    final List<ShipSprite> foundAliveShips = this.animatedSpriteField.stream()
            .filter(x -> x instanceof ShipSprite)
            .map(x -> (ShipSprite) x)
            .filter(x -> !x.isDestroyed())
            .collect(Collectors.toCollection(ArrayList::new));
    if (foundAliveShips.isEmpty()) {
      throw new IllegalStateException("Unexpected fire request without alive ships");
    } else {
      foundAliveShips.stream().filter(x -> x.getShipType() == ShipType.DREADNOUGHT)
              .findFirst().ifPresent(dreadnought -> {
                // increasing probability of dreadnought shot
                IntStream.range(0, foundAliveShips.size() / 4).forEach(i -> foundAliveShips.add(dreadnought));
              });
      foundAliveShips.stream().filter(x -> x.getShipType() == ShipType.AIR_CARRIER)
              .findFirst().ifPresent(carrier -> {
                // increasing probability of air-carrier shot
                IntStream.range(0, foundAliveShips.size() / 4).forEach(i -> foundAliveShips.add(carrier));
              });
      Collections.shuffle(foundAliveShips, RND);
      final ShipSprite firingShip = foundAliveShips.remove(0);
      firingShip.fire();
      return firingShip.getShipType();
    }
  }

  private void onTimer() {
    this.animatedSpriteField.forEach(FieldSprite::nextFrame);
    if (this.activeDecorationSprite != null) {
      this.activeDecorationSprite.nextFrame();
    }

    if (this.fieldWaterEffect != null) {
      this.fieldWaterEffect.nextFrame();
    }

    if (this.prevControl != this.selectedControl) {
      this.controlTicksCounter--;
      if (this.controlTicksCounter <= 0) {
        this.prevControl = this.selectedControl;
        this.doProcessGameControl(this.selectedControl);
      }
    }

    switch (this.currentStage) {
      case PLACEMENT_START: {
        if (this.stageStep < E1_NEW.getLength() - 1) {
          this.stageStep++;
        } else {
          this.initStage(Stage.PLACING);
        }
      }
      break;
      case PLACEMENT_END_ANIMATION: {
        if (this.stageStep < E1_NEW.getLength() - 1) {
          this.stageStep++;
        } else {
          this.fireEventToOpponent(new BsGameEvent(EVENT_ARRANGEMENT_COMPLETED, 0, 0));
          this.initStage(Stage.PLACEMENT_COMPLETED);
        }
      }
      break;
      case PLACEMENT_COMPLETED: {
        this.findGameEventInQueue(EnumSet.of(
                        EVENT_OPPONENT_FIRST_TURN,
                        EVENT_DO_TURN
                ))
                .ifPresent(e -> {
                  if (e.getType() == EVENT_OPPONENT_FIRST_TURN) {
                    this.fireEventToOpponent(new BsGameEvent(GameEventType.EVENT_DO_TURN, 0, 0));
                    this.initStage(Stage.ENEMY_TURN);
                  } else if (e.getType() == EVENT_DO_TURN) {
                    this.initStage(Stage.PANEL_ENTER);
                  } else {
                    this.processUnexpectedEvent(e);
                  }
                });
      }
      break;
      case WAIT_FOR_TURN: {
        this.findGameEventInQueue(EnumSet.of(GameEventType.EVENT_DO_TURN))
                .ifPresent(e -> {
                  if (e.getType() == EVENT_DO_TURN) {
                    this.initStage(Stage.PANEL_ENTER);
                  } else {
                    this.processUnexpectedEvent(e);
                  }
                });
      }
      break;
      case PANEL_ENTER: {
        if (this.stageStep < E1_NEW.getLength() - 1) {
          this.stageStep++;
        } else {
          this.initStage(Stage.TARGET_SELECT);
        }
      }
      break;
      case PLACING:
      case TARGET_SELECT: {
        this.findGameEventInQueue(Collections.emptySet())
                .ifPresent(this::processUnexpectedEvent);
      }
      break;
      case PANEL_EXIT: {
        if (this.stageStep < E1_NEW.getLength() - 1) {
          this.stageStep++;
        } else {
          this.lastFiringShipType.set(this.activateShipFire());
          this.initStage(Stage.FIRING);
        }
      }
      break;
      case FIRING: {
        this.activeDecorationSprite = null;
        if (this.noAnyFiringShip()) {
          this.sendFireNotificationForTargetCell(this.lastFiringShipType.get());
          this.initStage(Stage.FIRING_RESULT);
        }
      }
      break;
      case FIRING_RESULT: {
        if (this.activeDecorationSprite == null) {
          this.findGameEventInQueue(EnumSet
                  .of(GameEventType.EVENT_KILLED, EVENT_MISS, EVENT_HIT,
                          EVENT_LOST)).ifPresent(e -> {
            this.savedGameEvent.set(Optional.of(e));
            switch (e.getType()) {
              case EVENT_LOST:
              case EVENT_KILLED:
              case EVENT_HIT: {
                this.activeDecorationSprite =
                        new DecorationSprite(HORIZONS_EXPLOSION_COORDS, Animation.EXPLO_GOR,
                                Sound.OUR_EXPLODE_ONLY);
                if (e.getType() != EVENT_HIT) {
                  Sound.BUBBLES.play();
                }
              }
              break;
              case EVENT_MISS: {
                this.activeDecorationSprite =
                        new DecorationSprite(HORIZONS_SPLASH_COORDS, SPLASH_GOR,
                                Sound.OUR_WATER_SPLASH_ONLY);
              }
              break;
              default:
                this.processUnexpectedEvent(e);
                break;
            }
          });
        } else if (this.activeDecorationSprite.isCompleted()) {
          this.activeDecorationSprite = null;
          this.savedGameEvent.getAndSet(Optional.empty()).ifPresent(e -> {
            switch (e.getType()) {
              case EVENT_MISS: {
                this.gameField.setState(e.getX(), e.getY(), GameField.CellState.MISS);
                this.fireEventToOpponent(new BsGameEvent(GameEventType.EVENT_DO_TURN, 0, 0));
                this.initStage(Stage.ENEMY_TURN);
              }
              break;
              case EVENT_LOST: {
                this.fireSignal(SIGNAL_VICTORY);
              }
              break;
              default: {
                if (e.getType() == EVENT_KILLED) {
                  this.gameField.setState(e.getX(), e.getY(), GameField.CellState.KILL);
                  // kill
                  final List<Point> removedShip =
                          this.gameField.tryRemoveShipAt(new Point(e.getX(), e.getY()));
                  if (removedShip.isEmpty()) {
                    this.fireEventToOpponent(
                            new BsGameEvent(GameEventType.EVENT_FAILURE, 0, 0));
                    throw new IllegalStateException("Can't remove killed enemy ship from map: " + e);
                  } else {
                    removedShip
                            .forEach(c -> this.gameField.setState(c.x, c.y, GameField.CellState.KILL));
                    this.gameField.ensureBanAroundShips();
                  }
                } else {
                  // hit
                  this.gameField.setState(e.getX(), e.getY(), GameField.CellState.HIT);
                }
                this.initStage(Stage.WAIT_FOR_TURN);
              }
              break;
            }
          });
        }
      }
      break;
      case ENEMY_TURN: {
        if (this.activeFallingObjectSprite == null) {
          this.findGameEventInQueue(
                          EnumSet.of(GameEventType.EVENT_SHOT_MAIN, GameEventType.EVENT_SHOT_REGULAR))
                  .ifPresent(e -> {
                    if (e.getType() == EVENT_SHOT_MAIN || e.getType() == EVENT_SHOT_REGULAR) {
                      this.savedGameEvent.set(Optional.of(e));
                      final Optional<ShipSprite> hitShip = this.findShipForCell(e.getX(), e.getY());
                      final Point targetCell = hitShip.map(
                              FieldSprite::getActionCell).orElse(new Point(e.getX(), e.getY()));

                      if (e.getType() == EVENT_SHOT_MAIN) {
                        this.activeFallingObjectSprite =
                                new FallingAirplaneSprite(hitShip, targetCell);
                      } else {
                        this.activeFallingObjectSprite =
                                new FallingRocketSprite(hitShip, targetCell);
                      }
                      this.animatedSpriteField.add(this.activeFallingObjectSprite);
                      Collections.sort(this.animatedSpriteField);
                    } else {
                      this.processUnexpectedEvent(e);
                    }
                  });
        } else if (this.activeFallingObjectSprite.isCompleted()) {
          this.animatedSpriteField.remove(this.activeFallingObjectSprite);
          Collections.sort(this.animatedSpriteField);
          this.activeFallingObjectSprite = null;
          this.initStage(Stage.ENEMY_FIRING_RESULT);
        }
      }
      break;
      case ENEMY_FIRING_RESULT: {
        if (this.fieldWaterEffect == null) {
          this.savedGameEvent.getAndSet(Optional.empty()).ifPresent(enemyShoot -> {
            boolean enemyMayTurn = false;
            final BsGameEvent enemyTurnResultEvent;
            final ShipSprite hitShip = this.processEnemyShot(enemyShoot).orElse(null);
            if (hitShip == null) {
              enemyTurnResultEvent = new BsGameEvent(EVENT_MISS, enemyShoot.getX(),
                      enemyShoot.getY());
              this.fieldWaterEffect = new OneTimeWaterEffectSprite(
                      new Point(enemyShoot.getX(), enemyShoot.getY()), Optional.empty(),
                      Animation.SPLASH);
              Sound.WATER_SPLASH01.play();
            } else {
              enemyMayTurn = true;
              if (hitShip.isDestroyed()) {
                final GameEventType resultType;
                if (this.isThereAnyAliveShip()) {
                  resultType = EVENT_KILLED;
                } else {
                  enemyMayTurn = false;
                  resultType = EVENT_LOST;
                }
                enemyTurnResultEvent = new BsGameEvent(resultType,
                        enemyShoot.getX(),
                        enemyShoot.getY());
                Sound.BUBBLES.play();
              } else {
                enemyTurnResultEvent = new BsGameEvent(EVENT_HIT, enemyShoot.getX(),
                        enemyShoot.getY());
              }
              this.fieldWaterEffect = new OneTimeWaterEffectSprite(
                      hitShip.getActionCell(), Optional.of(hitShip), Animation.EXPLODE);
              Sound.EXPLODE01.play();
            }

            this.animatedSpriteField.add(this.fieldWaterEffect);
            Collections.sort(this.animatedSpriteField);
            this.fireEventToOpponent(enemyTurnResultEvent);

            if (enemyMayTurn) {
              this.fireEventToOpponent(new BsGameEvent(EVENT_DO_TURN, 0, 0));
            }
            this.savedGameEvent.set(Optional.of(enemyTurnResultEvent));
          });
        } else {
          if (this.fieldWaterEffect.isCompleted()) {
            this.animatedSpriteField.remove(this.fieldWaterEffect);
            if (this.fieldWaterEffect.getAnimation() == SPLASH) {
              final FishSprite fishSprite = new FishSprite(this.fieldWaterEffect.getCell());
              this.animatedSpriteField.add(fishSprite);
              Collections.sort(this.animatedSpriteField);
            }
            this.fieldWaterEffect = null;
            this.savedGameEvent.getAndSet(Optional.empty()).ifPresent(event -> {
              if (event.getType() == EVENT_LOST) {
                this.fireSignal(SIGNAL_LOST);
              } else {
                this.initStage(event.getType() == EVENT_MISS ? Stage.WAIT_FOR_TURN :
                        Stage.ENEMY_TURN);
              }
            });
          }
        }
      }
      break;
      default: {
        throw new IllegalStateException("Unexpected stage: " + this.currentStage);
      }
    }
    this.refreshUi();
  }

  private void processUnexpectedEvent(final BsGameEvent unexpectedEvent) {
    switch (unexpectedEvent.getType()) {
      case EVENT_FAILURE: {
        this.fireSignal(SIGNAL_SYSTEM_FAILURE);
      }
      break;
      case EVENT_CONNECTION_ERROR:
      case EVENT_GAME_ROOM_CLOSED: {
        this.fireSignal(SIGNAL_PLAYER_IS_OUT);
      }
      break;
      default: {
        throw new IllegalArgumentException("Non-processed unexpected event: " + unexpectedEvent);
      }
    }
  }

  private boolean noAnyFiringShip() {
    return this.animatedSpriteField.stream()
            .noneMatch(x -> x instanceof ShipSprite && ((ShipSprite) x).isFiring());
  }

  private boolean isThereAnyAliveShip() {
    return this.animatedSpriteField.stream()
            .anyMatch(s -> s instanceof ShipSprite && !((ShipSprite) s).isDestroyed());
  }

  private Optional<ShipSprite> findShipForCell(final int x, final int y) {
    final Point cell = new Point(x, y);
    return this.animatedSpriteField.stream()
            .filter(s -> s instanceof ShipSprite && s.containsCell(cell))
            .map(s -> (ShipSprite) s)
            .findFirst();
  }

  private Optional<ShipSprite> processEnemyShot(final BsGameEvent e) {
    final Optional<ShipSprite> hitShip = this.findShipForCell(e.getX(), e.getY());
    hitShip.ifPresent(ShipSprite::processHit);
    return hitShip;
  }

  @Override
  protected void doDispose() {
    Sound.stopAll();
    this.timer.stop();
    this.disposePlayer();
  }

  private void drawNumberOfShipsOnPanel(final Graphics2D g2d,
                                        final int cell4,
                                        final int cell3,
                                        final int cell2,
                                        final int cell1,
                                        final int panelY) {
    g2d.drawImage(Animation.DIGIT.getFrame(cell4), null, 8, panelY + 97);
    g2d.drawImage(Animation.DIGIT.getFrame(cell3), null, 8, panelY + 202);
    g2d.drawImage(Animation.DIGIT.getFrame(cell2), null, 8, panelY + 299);
    g2d.drawImage(Animation.DIGIT.getFrame(cell1), null, 8, panelY + 394);
  }

  private void drawFish(final Graphics2D g) {
    this.animatedSpriteField.stream().filter(x -> x instanceof FishSprite)
            .forEach(x -> x.render(g));
  }

  private void drawAllExcludeFish(final Graphics2D g) {
    this.animatedSpriteField.stream().filter(x -> !(x instanceof FishSprite))
            .forEach(x -> x.render(g));
  }

  @Override
  protected void doPaint(final Graphics2D g2d) {
    g2d.drawImage(this.background, null, 0, 0);
    if (this.activeDecorationSprite != null) {
      this.activeDecorationSprite.render(g2d);
    }
    this.drawFish(g2d);
    this.drawAllExcludeFish(g2d);

    switch (this.currentStage) {
      case PLACEMENT_START: {
        final int dx = round((PANEL.getWidth() / (float) E1_NEW.getLength()) * this.stageStep);
        g2d.drawImage(PANEL.getLast(), null, dx - PANEL.getWidth(), 100);
        g2d.drawImage(E1_NEW.getFrame(this.stageStep), null, 0, 0);
        g2d.drawImage(E2_NEW.getFrame(this.stageStep), null, 512, 0);
      }
      break;
      case PLACING: {
        g2d.drawImage(PANEL.getLast(), null, 0, 100);
        g2d.drawImage(E1_NEW.getLast(), null, 0, 0);
        g2d.drawImage(E2_NEW.getLast(), null, 512, 0);
        this.currentStage.getBanner().render(g2d, BANNER_COORD);
        this.drawNumberOfShipsOnPanel(g2d, this.gameField.getShipsCount(ShipType.AIR_CARRIER),
                this.gameField.getShipsCount(ShipType.DREADNOUGHT),
                this.gameField.getShipsCount(ShipType.GUARD_SHIP),
                this.gameField.getShipsCount(ShipType.U_BOAT), 100);
        this.renderActionPanel(g2d, ACTION_PANEL_AREA.x, ACTION_PANEL_AREA.y, this.gameField, true);
      }
      break;
      case PLACEMENT_END_ANIMATION: {
        final int dx = round((PANEL.getWidth() / (float) E1_NEW.getLength()) * this.stageStep);
        g2d.drawImage(PANEL.getLast(), null, -dx, 100);
        g2d.drawImage(E1_NEW.getFrame(E1_NEW.getLength() - this.stageStep - 1),
                null, 0, 0);
        g2d.drawImage(E2_NEW.getFrame(E1_NEW.getLength() - this.stageStep - 1),
                null, 512, 0);
      }
      break;
      case TARGET_SELECT: {
        g2d.drawImage(E1_NEW.getLast(), null, 0, 0);
        g2d.drawImage(E2_NEW.getLast(), null, 512, 0);
        g2d.drawImage(FIRE.getFirst(), null, 136, 0);
        this.renderActionPanel(g2d, 287, 119, this.gameField, false);
        this.currentStage.getBanner().render(g2d, BANNER_COORD);
      }
      break;
      case PANEL_ENTER: {
        g2d.drawImage(E1_NEW.getFrame(this.stageStep), null, 0, 0);
        g2d.drawImage(E2_NEW.getFrame(this.stageStep), null, 512, 0);
      }
      break;
      case PLACEMENT_COMPLETED:
      case FIRING:
      case WAIT_FOR_TURN:
      case ENEMY_TURN:
      case ENEMY_FIRING_RESULT:
      case FIRING_RESULT: {
        g2d.drawImage(E1_NEW.getFirst(), null, 0, 0);
        g2d.drawImage(E2_NEW.getFirst(), null, 512, 0);
        this.currentStage.getBanner().render(g2d, BANNER_COORD);
      }
      break;
      case PANEL_EXIT: {
        g2d.drawImage(E1_NEW.getFrame(E1_NEW.getLength() - this.stageStep - 1),
                null, 0, 0);
        g2d.drawImage(E2_NEW.getFrame(E1_NEW.getLength() - this.stageStep - 1),
                null, 512, 0);
      }
      break;
      default: {
        throw new IllegalStateException("Unexpected stage: " + this.currentStage);
      }
    }

    switch (this.selectedControl) {
      case PAUSE: {
        g2d.drawImage(DONE_AUTO.getFrame(1), null, 8, 0);
        g2d.drawImage(PAUSE_EXIT.getFirst(), null, 544, 344);
      }
      break;
      case EXIT: {
        g2d.drawImage(DONE_AUTO.getFrame(1), null, 8, 0);
        g2d.drawImage(PAUSE_EXIT.getLast(), null, 544, 344);
      }
      break;
      case AUTO:
      case DONE: {
        final BufferedImage controlImage =
                this.selectedControl == ControlElement.DONE ? DONE_AUTO.getFirst() :
                        DONE_AUTO.getLast();
        g2d.drawImage(controlImage, null, 8, 0);
        g2d.drawImage(PAUSE_EXIT.getFrame(1), null, 544, 344);
      }
      break;
      default: {
        g2d.drawImage(DONE_AUTO.getFrame(1), null, 8, 0);
        g2d.drawImage(PAUSE_EXIT.getFrame(1), null, 544, 344);
      }
      break;
    }

  }

  private void doProcessGameControl(final ControlElement control) {
    switch (control) {
      case AUTO: {
        this.gameField.autoPlacingFreeShips();
        this.doSelectControl(ControlElement.NONE);
      }
      break;
      case DONE: {
        this.doSelectControl(ControlElement.NONE);
        this.animatedSpriteField = this.gameField.moveFieldToShipSprites();
        this.gameField.reset();
        LOGGER.info("Ready");
        this.fireEventToOpponent(new BsGameEvent(EVENT_READY, RND.nextInt(), RND.nextInt()));
        this.initStage(Stage.PLACEMENT_END_ANIMATION);
      }
      break;
      case NEUTRAL: {
        this.doSelectControl(ControlElement.NONE);
      }
      break;
      case PAUSE: {
        this.fireSignal(SIGNAL_PAUSED);
      }
      break;
      case EXIT: {
        final Timer timer = new Timer(1500, e -> this.fireSignal(SIGNAL_EXIT));
        timer.setRepeats(false);
        timer.start();
      }
      break;
      case VICTORY: {
        this.fireSignal(SIGNAL_VICTORY);
      }
      break;
      case LOST: {
        this.fireSignal(SIGNAL_LOST);
      }
      break;
    }
  }

  @Override
  public void onGameKeyEvent(final KeyEvent e) {
    if (e.getKeyCode() == KeyEvent.VK_SPACE && this.currentStage == Stage.PLACING) {
        this.gameField.reset();
        refreshUi();
    }
  }

  private enum Stage {
    PLACEMENT_START(InfoBanner.NONE),
    PLACING(InfoBanner.PLACEMENT),
    PLACEMENT_END_ANIMATION(InfoBanner.NONE),
    PLACEMENT_COMPLETED(InfoBanner.WAIT_OPPONENT),
    WAIT_FOR_TURN(InfoBanner.WAIT_OPPONENT),
    PANEL_ENTER(InfoBanner.NONE),
    TARGET_SELECT(InfoBanner.YOUR_MOVE),
    PANEL_EXIT(InfoBanner.NONE),
    FIRING(InfoBanner.YOUR_MOVE),
    FIRING_RESULT(InfoBanner.YOUR_MOVE),
    ENEMY_TURN(InfoBanner.OPPONENTS_MOVE),
    ENEMY_FIRING_RESULT(InfoBanner.OPPONENTS_MOVE);
    private final InfoBanner banner;

    Stage(final InfoBanner banner) {
      this.banner = banner;
    }

    public InfoBanner getBanner() {
      return this.banner;
    }
  }


}
