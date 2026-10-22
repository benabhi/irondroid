# Banco de pruebas de la narración · prompt v6

Corrido el 21/10/2026 22:14 con `deepseek-flash`, esfuerzos low, high, max. Kira (femenino), filo 2, corazón 1, hierro 2, sombra 1, ingenio 3; un turno previo en Graywatch con Cera. Los dados de cada escena están fijados para forzar el resultado. Generado por `NarrationBench` (IRON-110).

## Resumen por esfuerzo

| Esfuerzo | Escenas que pasan todo | Chequeos que pasan | Tiempo medio | Tokens de entrada (en caché) | Tokens de salida | Costo total |
|---|---|---|---|---|---|---|
| low | 7/8 | 65/66 | 6.2 s | 125904 (115200) | 3405 | US$ 0.0080 |
| high | 8/8 | 66/66 | 7.3 s | 132651 (121600) | 5193 | US$ 0.0103 |
| max | 8/8 | 66/66 | 8.4 s | 145808 (134656) | 6531 | US$ 0.0120 |

## Chequeos por escena

| Escena | low | high | max |
|---|---|---|---|
| Llegar a un lugar nuevo | ✓ | ✓ | ✓ |
| Turno sin riesgo | ✓ | ✓ | ✓ |
| Éxito parcial con elección | ✓ | ✓ | ✓ |
| Fallo con Pagar el precio | ✓ | ✓ | ✓ |
| Fallo con doble | ✓ | ✓ | ✓ |
| Pedir un movimiento | ✓ | ✓ | ✓ |
| Corrección | ✓ | ✓ | ✓ |
| Pedido fuera de la ficción | ✗ Largo (2 a 5 oraciones) | ✓ | ✓ |

## Narraciones

### Llegar a un lugar nuevo

> Entro a la aldea vecina, que nunca visité, y busco a alguien que me venda provisiones.

**low** · 5.7 s · 9426 → 428 tokens · US$ 0.0016

Herramientas: `roll_oracle_table` Saqueador; `roll_oracle_table` Cobarde; `roll_oracle_table` Asha

> Dentro de la empalizada el aire huele a humo de turba y pescado seco. Solo hay un puesto con sacos abiertos: Asha, una mujer que no deja de mirar hacia la puerta, vende harina, sal y unas botas de cuero que todavía llevan barro ajeno. —No preguntes de dónde salió nada y el precio no sube —dice, sin soltar el cuchillo que tiene junto a la balanza. ¿Qué haces?


**high** · 8.2 s · 10289 → 1104 tokens · US$ 0.0026

Herramientas: `get_move` Rules of Reabastecerse; `get_scene` No scene is tracked yet; `roll_oracle_table` Saqueador; `roll_oracle_table` Tira dos veces. / Refutar una mentira / Vengar una afrenta; `roll_oracle_table` Juramentado de hierro; `roll_oracle_table` Myrtle

> En el callejón de barro, un carro tapado con pieles huele a sebo y a hierro viejo; detrás, una mujer de trenzas con anillos de metal te mira de arriba abajo como si tasara una res. —Myrtle —dice, sin extender la mano—. Vendo lo que otros perdieron, y no pregunto de dónde. ¿Qué buscas?


**max** · 12.7 s · 33923 → 934 tokens · US$ 0.0031

Herramientas: `get_scene` No scene is tracked yet; `get_tracks` 0 progress tracks; `get_move` Rules of Reabastecerse; `roll_move` Reabastecerse: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_oracle_table` Es estresante.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> La plaza de Graywatch huele a turba quemada y a pescado seco, pero las puertas se cierran a tu paso. Una mujer aparta a un niño del puesto de sal y una voz ronca grita desde el fondo que los forasteros vienen a llevarse lo que queda. Un hombre armado con un hacha de leñador te corta el camino entre los charcos.


### Turno sin riesgo

> Me siento junto al fuego de Cera y escucho lo que cuentan los aldeanos.

**low** · 4.9 s · 9226 → 443 tokens · US$ 0.0007

Herramientas: `roll_oracle_table` Liberar; `roll_oracle_table` Riqueza

> El fuego chisporrotea y una anciana de dedos manchados de hollín deja su rueca sobre las rodillas. —En el norte hay una mina vieja donde sacaban hierro para pagar deudas; alguien rompió el sello y algo salió —dice, y los demás callan—. Desde entonces, lo que baja cargado de hierro no vuelve. Cera escupe en las brasas sin añadir nada. ¿Qué haces?


**high** · 5.2 s · 9320 → 490 tokens · US$ 0.0008

Herramientas: `roll_oracle_table` Liberar; `roll_oracle_table` Riqueza

> El fuego chisporrotea y los aldeanos hablan bajo, como si las paredes escucharan: los que tomaron el camino del norte no murieron, los tienen vivos, y alguien manda hierro a cambio de soltarlos. Una anciana escupe en las brasas —Pagan por ellos como si fueran ganado, y el que paga, cobra luego— y todos bajan la vista hacia el cofre de la aldea. Cera no aparta los ojos de ti. ¿Qué haces?


**max** · 6.0 s · 9320 → 578 tokens · US$ 0.0009

Herramientas: `roll_oracle_table` Liberar; `roll_oracle_table` Riqueza

> El fuego chisporrotea y las voces bajan cuando Cera habla de un hombre que volvió del norte hace dos inviernos con plata suficiente para liberar a su familia de una deuda vieja, y que desde entonces no dice una palabra de lo que vio. Otros aldeanos murmuran que ahora más de uno mira hacia ese camino con hambre de riqueza. Cera escupe en las brasas: —La plata del norte se paga con algo, forastera. ¿Qué haces?


### Éxito parcial con elección

> Intento convencer al guardia de la empalizada de que me deje salir de noche.

**low** · 6.7 s · 20423 → 373 tokens · US$ 0.0009

Herramientas: `get_move` Rules of Enfrentar el peligro; `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_supply

> El guardia te escucha con la lanza cruzada, y su aliento sale en vaho mientras sopesa tus palabras. —La puerta no se abre gratis —dice, mirando tu mochila—. Ni por juramentos.


**high** · 7.1 s · 14820 → 573 tokens · US$ 0.0010

Herramientas: `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_supply

> El guardia escupe al suelo y te mira de arriba abajo, sin prisa. —La puerta la abro yo, y lo que yo abro, se paga —dice, y tiende la mano abierta hacia tu carga. Detrás de él, los cerrojos de hierro esperan, negros de grasa vieja.


**max** · 6.0 s · 14785 → 549 tokens · US$ 0.0010

Herramientas: `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_supply

> El guardia escucha con la lanza cruzada en la puerta, los ojos yendo de tu rostro a la oscuridad del camino. —No soy yo quien cierra —dice, y se lame los labios—. Pero un hombre que no vio nada tampoco tiene que contarlo. Su mano libre se abre, vacía, y espera.


### Fallo con Pagar el precio

> Salto el barranco helado para cortar camino.

**low** · 6.1 s · 19418 → 384 tokens · US$ 0.0009

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El borde del barranco se desmorona bajo tus botas y el hielo suena hueco, como un tambor rajado. Abajo, entre las piedras, espera la grieta abierta que el frío no ha cerrado del todo.


**high** · 5.8 s · 19281 → 352 tokens · US$ 0.0008

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El hielo cruje como una lámina de estaño y una grieta corre bajo tus botas hacia la orilla. Al fondo del barranco, entre piedras negras, algo se mueve despacio bajo la escarcha, y el viento trae un olor dulzón y podrido.


**max** · 6.8 s · 19562 → 528 tokens · US$ 0.0010

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Tomas impulso y el borde se deshace bajo tus botas en una lluvia de nieve vieja. En el aire ves el hielo del fondo, gris y surcado de vetas, y una grieta oscura que se abre justo donde vas a caer.


### Fallo con doble

> Me lanzo contra el bandido que bloquea el puente.

**low** · 11.3 s · 33758 → 798 tokens · US$ 0.0020

Herramientas: `get_scene` No scene is tracked yet; `get_tracks` 0 progress tracks; `create_progress_track` Started combat track "El bandido del puente"; `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_oracle_table` Dos situaciones que parecían no tener relación resultan estar conectadas.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El barro del puente cede bajo tu bota y el bandido se aparta con un salto, riéndose. Detrás de ti crujen las tablas: una figura baja del extremo de Graywatch con una lanza en la mano, y antes de verle la cara ya conoces el andar del hombre que te vendió provisiones esta tarde. Te quedan la barandilla rota y el río oscuro a la espalda.


**high** · 14.8 s · 34763 → 1403 tokens · US$ 0.0028

Herramientas: `get_scene` No scene is tracked yet; `search_journal` 0 journal entries; `create_progress_track` Started combat track "El bandido del puente"; `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_oracle_table` Dos situaciones que parecían no tener relación resultan estar conectadas.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Arrancas hacia el puente y el tablón podrido cede bajo tu pie; una cuerda tensa entre los postes te siega las piernas y caes de bruces sobre la madera. El bandido no huye: sonríe y se lleva dos dedos a la boca para silbar, y en el hilván de su capa ves la misma lana gris con que Graywatch te recibió en la puerta.


**max** · 16.0 s · 27825 → 1930 tokens · US$ 0.0028

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_oracle_table` Dos situaciones que parecían no tener relación resultan estar conectadas.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Te lanzas con el hombro por delante, pero las tablas mojadas te traicionan y el bandido se aparta con un gesto tranquilo, como quien ya ha visto esa carga antes. Al pasar a su lado ves el cordón gris anudado a su muñeca, el mismo que llevan los hombres de Cera en Graywatch: los asaltos del camino y la aldea cerrada son la misma mano. El hombre se lleva dos dedos a la boca y silba, y el puente entero se estremece bajo tus pies.


### Pedir un movimiento

> Hago Reunir información +Ingenio

**low** · 3.7 s · 9185 → 163 tokens · US$ 0.0004

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4)

> Cera escupe al suelo y señala el paso con la barbilla: —La que manda es la vieja Hesta, pero lleva tres días sin salir de la casa larga. Habla con su hija, la que carga el agua: ella sabe qué se pudre ahí dentro. La mercenaria te mira de arriba abajo y añade que nadie paga por subir al paso, solo por lo que traigan de vuelta. ¿Qué haces?


**high** · 3.2 s · 9185 → 186 tokens · US$ 0.0004

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4)

> Cera escupe al suelo y señala con la barbilla la empalizada norte. —Los que suben al paso no vuelven, y no es el frío —dice, y te cuenta que hace dos inviernos una partida de mercenarios subió a cobrar deudas y desde entonces alguien enciende fuegos en las ruinas del alto, de noche, para llamar a más. Los aldeanos ya no entierran a sus muertos, solo cierran las puertas. ¿Qué haces?


**max** · 5.6 s · 9185 → 559 tokens · US$ 0.0008

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4)

> Cera escupe al suelo y baja la voz: la que manda aquí es la anciana del salón largo, pero desde que el camino del norte se tragó a seis, nadie la ve reír. —Alguien volvió —dice, mirando la empalizada—. Lo tienen en un establo, y no cuenta nada bueno. ¿Qué haces?


### Corrección

> Cruzo el río helado a nado.  ✎ Kira no sabe nadar: busca un vado.

**low** · 8.0 s · 19990 → 630 tokens · US$ 0.0012

Herramientas: `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Buscas el paso tanteando la orilla con la punta de la bota, donde el agua rompe en hielo delgado y la corriente suena más honda. Un tramo de grava asoma bajo la escarcha, y al dar el primer paso hacia él la superficie cruje con un chasquido largo, como una rama que se parte despacio. Bajo tus pies, el hielo se hunde un dedo y el agua negra empieza a subir por las grietas.


**high** · 11.0 s · 30515 → 774 tokens · US$ 0.0015

Herramientas: `get_scene` No scene is tracked yet; `get_tracks` 0 progress tracks; `search_journal` 0 journal entries; `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Bajas por la orilla buscando un vado, golpeando la escarcha con un palo. En el recodo el río se ensancha y el hielo parece firme, blanco y liso como una piedra. Das un paso, y algo bajo la nieve cruje como una rama seca.


**max** · 11.1 s · 26730 → 1117 tokens · US$ 0.0018

Herramientas: `get_scene` No scene is tracked yet; `search_journal` 0 journal entries; `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El río es una sábana gris con vetas negras donde la corriente no se ha cerrado, y la orilla opuesta se pierde tras la bruma. Golpeas el hielo con el pie, palmo a palmo, buscando un paso firme. La costra cruje, se abre en una grieta larga y la bota se te hunde hasta la rodilla en el agua negra que tira hacia abajo.


### Pedido fuera de la ficción

> Decime cuántos puntos de salud tengo y explicame las reglas de Enfrentar el peligro.

**low** · 2.8 s · 4478 → 186 tokens · US$ 0.0003

> Cera te mira de arriba abajo, impaciente, y el frío de Graywatch se te cuela por la ropa. Sientes el cuerpo entero y sin heridas abiertas, el aliento firme; tu hoja y tu estado están anotados en la hoja del personaje, con todos sus detalles. Enfrentar el peligro es lo que haces cuando el risco cede bajo tu bota o una mano te busca en la oscuridad: lo resuelve la lista de movimientos, con la tirada que corresponda. —¿Vienes por el paso o por las camas? —gruñe Cera. ¿Qué haces?

Falló: Largo (2 a 5 oraciones) (6 oraciones)

**high** · 3.1 s · 4478 → 311 tokens · US$ 0.0005

> Sientes el hombro aún resentido por el camino y el frío metido en los huesos; tu cuerpo aguanta, pero no de sobra, y los detalles están en tu hoja. Enfrentar el peligro es lo que haces cuando el risco cede bajo la bota o una mano se cierra sobre tu muñeca: decides si confías en tu rapidez, en tu fuerza o en tu astucia, y el mundo responde, a veces a tu favor y a veces con creces; la lista de movimientos lo explica mejor de lo que yo puedo. Cera te observa mientras espera tu respuesta, con el aliento humeando en el aire gris. ¿Qué haces?


**max** · 3.0 s · 4478 → 336 tokens · US$ 0.0005

> Kira se yergue sin heridas abiertas ni el peso del cansancio en los huesos: el cuerpo le responde entero, aunque el frío de Graywatch ya le muerde los dedos. Enfrentar el peligro es lo que hace cuando el camino exige un salto, una carrera o aguantar el golpe de algo que no perdona un error, y siempre puede costar algo aunque salga bien; la hoja y la lista de movimientos tienen los detalles. —Aquí nadie te va a curar gratis —dice Cera desde la empalizada, mirando el horizonte—. ¿Qué haces?

