# Banco de pruebas de la narración · prompt v5

Corrido el 21/10/2026 22:01 con `deepseek-flash`, esfuerzos low, high, max. Kira (femenino), filo 2, corazón 1, hierro 2, sombra 1, ingenio 3; un turno previo en Graywatch con Cera. Los dados de cada escena están fijados para forzar el resultado. Generado por `NarrationBench` (IRON-110).

## Resumen por esfuerzo

| Esfuerzo | Escenas que pasan todo | Chequeos que pasan | Tiempo medio | Tokens de entrada (en caché) | Tokens de salida | Costo total |
|---|---|---|---|---|---|---|
| low | 6/8 | 62/66 | 5.5 s | 119204 (112256) | 3934 | US$ 0.0037 |
| high | 5/8 | 63/66 | 7.3 s | 135469 (128384) | 4986 | US$ 0.0065 |
| max | 7/8 | 64/66 | 9.3 s | 144859 (137088) | 7218 | US$ 0.0118 |

## Chequeos por escena

| Escena | low | high | max |
|---|---|---|---|
| Llegar a un lugar nuevo | ✓ | ✗ Tira en las tablas antes de inventar | ✓ |
| Turno sin riesgo | ✓ | ✗ Termina con una pregunta | ✓ |
| Éxito parcial con elección | ✓ | ✓ | ✓ |
| Fallo con Pagar el precio | ✓ | ✓ | ✓ |
| Fallo con doble | ✗ Sin pregunta con una elección abierta | ✓ | ✓ |
| Pedir un movimiento | ✓ | ✓ | ✓ |
| Corrección | ✓ | ✓ | ✓ |
| Pedido fuera de la ficción | ✗ Largo (2 a 5 oraciones); Un solo párrafo; Sin números | ✗ Sin números | ✗ Un solo párrafo; Sin números |

## Narraciones

### Llegar a un lugar nuevo

> Entro a la aldea vecina, que nunca visité, y busco a alguien que me venda provisiones.

**low** · 7.6 s · 19547 → 791 tokens · US$ 0.0007

Herramientas: `get_move` Rules of Reabastecerse; `ask_oracle` Yes (88 against fifty_fifty), extreme; `roll_oracle_table` Granjero; `roll_oracle_table` Desconfiado; `roll_oracle_table` Vengar una afrenta; `roll_oracle_table` Chandra

> Chandra, una granjera de mirada estrecha y las manos manchadas de tierra, te estudia desde el umbral de su cobertizo antes de dejarte pasar. Dentro cuelgan racimos de nabos secos y tiras de carne de cabra; hay más de lo que la aldea aparenta tener. —El camino del norte se llevó a mi hermano —dice, sin apartar los ojos de tu arma—. ¿Qué buscas, forastera?


**high** · 11.1 s · 25990 → 1199 tokens · US$ 0.0010

Herramientas: `get_move` Rules of Reabastecerse; `roll_move` Reabastecerse: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Dentro de la empalizada el barro huele a humo y a grasa. La puerta del almacén está cerrada con una barra, y la mujer que la custodia escupe al suelo cuando nombras provisiones: —Aquí no vendemos a los que llegan con el camino del norte en la ropa. —Detrás de ti, varias figuras se han detenido junto a los fuegos, y las manos se mueven hacia las faldas de las túnicas, donde cuelgan los cuchillos.

Falló: Tira en las tablas antes de inventar (get_move:resupply, roll_move:resupply, roll_move:pay_the_price, suggest_option)

**max** · 9.8 s · 9645 → 1293 tokens · US$ 0.0018

Herramientas: `roll_oracle_table` Saqueador; `roll_oracle_table` Tira dos veces. / Refutar una mentira / Vengar una afrenta; `roll_oracle_table` Juramentado de hierro; `roll_oracle_table` Myrtle

> Bajo la empalizada, un cobertizo de pieles apesta a pescado ahumado y a sebo: hay sal, pan duro y cuerdas de buen fibrado. Myrtle, una saqueadora con anillos de hierro en las trenzas, aparta su banco para dejarte mirar la mercancía. —Dicen que todavía asalto el paso del norte —gruñe sin levantar la vista—. Quien lo dice miente, y pienso probarlo. ¿Qué haces?


### Turno sin riesgo

> Me siento junto al fuego de Cera y escucho lo que cuentan los aldeanos.

**low** · 3.7 s · 8811 → 310 tokens · US$ 0.0003

Herramientas: `roll_oracle_table` Liberar; `roll_oracle_table` Riqueza

> El fuego chisporrotea y una anciana de dedos hinchados habla sin que nadie se lo pida: hace dos inviernos unos hombres abrieron un túmulo al norte y sacaron plata a manos llenas —Lo que estaba guardado no era nuestro —murmura—, y ahora algo camina por el paso. Cera escupe en las brasas y no dice nada. ¿Preguntas algo más?


**high** · 2.9 s · 4282 → 300 tokens · US$ 0.0002

> El fuego chisporrotea y el humo se queda bajo el techo. Una vieja teje redes y habla sin levantar la vista: —Hace dos lunas el herrero fue al paso a buscar a su hijo. Volvió el hijo. El herrero no. Cera escupe al fuego y no dice nada, pero su mano se cierra sobre la empuñadura del cuchillo.

Falló: Termina con una pregunta (cierra sobre la empuñadura del cuchillo.)

**max** · 8.1 s · 13943 → 697 tokens · US$ 0.0011

Herramientas: `roll_oracle_table` Liberar; `roll_oracle_table` Riqueza; `roll_oracle_table` Elli

> El fuego chisporrotea y las voces bajan cuando una anciana llamada Elli cuenta lo que todos callaron: cada otoño el norte se lleva hierro y grano, y este año la aldea no tiene con qué pagar. —Si no lo entregamos, vendrán a buscarlo —dice, sin levantar los ojos del fuego—. ¿Qué haces?


### Éxito parcial con elección

> Intento convencer al guardia de la empalizada de que me deje salir de noche.

**low** · 5.0 s · 13936 → 407 tokens · US$ 0.0004

Herramientas: `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_supply

> El guardia escupe al suelo y mira la oscuridad tras la empalizada, luego a ti. —La puerta no se abre gratis, forastera —dice, con la lanza atravesada en tu camino—. Nadie sale de noche sin pagar por ello. Sopesa tu respuesta en silencio, esperando.


**high** · 6.8 s · 14420 → 776 tokens · US$ 0.0006

Herramientas: `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_momentum

> Cera no aparta la mano de la empalizada. Escupe al suelo y te mira de arriba abajo —Los del norte no vuelven, y los que abren la puerta tampoco —dice, sin mover un dedo del pestillo—. Detrás de ella, la antorcha chisporrotea y el viento trae un aullido largo desde el bosque.


**max** · 9.1 s · 14594 → 1024 tokens · US$ 0.0015

Herramientas: `roll_move` Enfrentar el peligro: weak hit (action 5 vs 3 and 8); the player picks 1; `suggest_option` Suggested lose_momentum

> El guardia es viejo y tiene las manos agrietadas de tirar del portón; no te mira a los ojos, mira la cuerda que sostiene entre los dedos. —Cera corta las manos de quien abre de noche —dice, sin convicción, más para sí mismo que para ti. La noche se espesa sobre la empalizada y en algún punto del camino norte aúllan los perros.


### Fallo con Pagar el precio

> Salto el barranco helado para cortar camino.

**low** · 5.6 s · 18459 → 300 tokens · US$ 0.0004

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El hielo cruje bajo tus botas, una grieta corre como un látigo y el borde del barranco se abre ante ti. Abajo, la nieve revuelta y las rocas esperan.


**high** · 8.7 s · 23867 → 540 tokens · US$ 0.0006

Herramientas: `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_oracle_table` Desperdicia recursos.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El hielo cruje y se abre bajo tu bota como una costura podrida. Caes, ruedas y trepas por el otro lado con las manos en carne viva, y solo al llegar arriba notas el peso que falta en tu espalda. Allá abajo, en el fondo del barranco, tu mochila se ha hundido entre placas negras de hielo.


**max** · 8.9 s · 23593 → 531 tokens · US$ 0.0011

Herramientas: `get_scene` No scene is tracked yet; `get_tracks` 0 progress tracks; `roll_move` Enfrentar el peligro: miss (action 3 vs 9 and 10); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Corres hacia el borde y saltas. El hielo del fondo no es la superficie firme que prometía la luz de la tarde: cruje, se hunde, y el agua negra te traga hasta el pecho con un golpe que corta el aliento. La mochila tira de tus hombros hacia abajo y las paredes del barranco, lisas y heladas, se alzan por encima de ti.


### Fallo con doble

> Me lanzo contra el bandido que bloquea el puente.

**low** · 8.9 s · 21129 → 1044 tokens · US$ 0.0010

Herramientas: `roll_oracle_table` Saqueador; `roll_oracle_table` Cobarde; `roll_oracle_table` Elli; `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `roll_oracle_table` The player has choice choice_1 open and must decide it first; `suggest_option` Suggested obvious_outcome

> Bajas el hombro y te lanzas, pero Elli es rápido: se escabulle tras los postes del puente sin presentar pelea y su grito rasga el aire —¡Aquí! ¡Aquí están! —. Del otro lado del río, entre los abetos, se mueven sombras que no estaban antes y el tablón bajo tus pies cruje. ¿Qué haces?

Falló: Sin pregunta con una elección abierta

**high** · 10.7 s · 24339 → 883 tokens · US$ 0.0015

Herramientas: `get_scene` No scene is tracked yet; `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_oracle_table` Dos situaciones que parecían no tener relación resultan estar conectadas.; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> El bandido no espera tu embestida: se escurre a un lado con una agilidad que no cuadra con su andar de borracho, y su mano libre ya busca tu cintura. La barandilla del puente cruje bajo tus botas.


**max** · 12.7 s · 29720 → 1145 tokens · US$ 0.0019

Herramientas: `get_scene` No scene is tracked yet; `search_journal` 0 journal entries; `roll_move` Enfrentar el peligro: miss (action 3 vs 8 and 8), a match; next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `roll_oracle_table` The player has choice choice_1 open and must decide it first; `suggest_option` Suggested obvious_outcome

> El bandido no huye: se aparta con una calma ensayada, y su mano busca una cuerda tensa junto al pretil. Bajo tus pies, los tablones crujen y se separan, y el río ruge en el hueco que se abre. El hombre ya está en la orilla, mirando cómo el puente se deshace bajo tu peso.


### Pedir un movimiento

> Hago Reunir información +Ingenio

**low** · 2.9 s · 8807 → 160 tokens · US$ 0.0002

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4)

> Cera escupe al suelo y señala el norte con la barbilla. —Los que suben al paso vuelven con los ojos vacíos, si vuelven. Y el pozo de la aldea se está secando, así que alguien tendrá que ir pronto —dice, y añade que una familia de leñadores vive en el límite del bosque y vio algo en la nieve. ¿Qué haces?


**high** · 3.6 s · 8799 → 166 tokens · US$ 0.0004

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4)

> Cera escupe al suelo y señala con la barbilla el camino —Tres semanas atrás se llevaron a la hija del herrero, y al herrero con ella. No vi más que sombras de piel de lobo—. Su mirada se pierde hacia el norte, donde las colinas se cierran sobre el paso. ¿Qué haces?


**max** · 7.6 s · 14088 → 888 tokens · US$ 0.0014

Herramientas: `roll_move` Reunir información: strong hit (action 8 vs 2 and 4); `roll_oracle_table` Saqueador; `roll_oracle_table` Tira dos veces. / Refutar una mentira / Vengar una afrenta; `roll_oracle_table` Juramentado de hierro; `roll_oracle_table` Myrtle

> Cera escupe al suelo y señala la casa larga del fondo: allí manda Myrtle, una vieja saqueadora que ahora jura por el hierro y no perdona. —No te fíes de su calma: anda buscando quién mintió sobre la incursión del año pasado —murmura, y añade que la puerta del norte sigue cerrada porque los de fuera no vuelven. Dentro, el humo del hogar te raspa la garganta y una mujer de trenzas grises afila un cuchillo sin levantar la vista. ¿Qué haces?


### Corrección

> Cruzo el río helado a nado.  ✎ Kira no sabe nadar: busca un vado.

**low** · 6.7 s · 19286 → 567 tokens · US$ 0.0005

Herramientas: `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Buscas un vado río arriba, donde el agua rompe en vetas oscuras sobre las piedras planas. Das el primer paso sobre el hielo del borde y algo cruje, una grieta que corre rápida hacia tus pies como una serpiente blanca. El agua oscura espera debajo, demasiado honda para alguien que no sabe nadar.


**high** · 10.4 s · 24505 → 765 tokens · US$ 0.0015

Herramientas: `get_scene` No scene is tracked yet; `search_journal` 0 journal entries; `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Avasallas la orilla hacia el norte, tanteando la nieve con una rama, hasta hallar un lugar donde unas piedras negras rompen la corriente. El hielo del borde cruje, pero aguanta bajo tu peso. Das un paso hacia el centro del río y el fondo blando se deshace como ceniza mojada: el agua te sube a los muslos, helada, y tira de ti hacia un hueco abierto en el hielo, más abajo.


**max** · 13.2 s · 29838 → 1102 tokens · US$ 0.0020

Herramientas: `get_scene` No scene is tracked yet; `search_journal` 0 journal entries; `roll_move` Enfrentar el peligro: miss (action 7 vs 7 and 9); next: Pagar el precio; `roll_move` Pagar el precio: done; the player picks 1; `suggest_option` Suggested obvious_outcome

> Sigues la orilla aguas abajo, tanteando con el pie donde la corriente suena más baja, y encuentras una lengua de guijarros que cruza hacia el otro lado. El hielo que la cubre es delgado, turbio, y cruje bajo tu peso como una puerta vieja. A mitad del río, una grieta negra se abre delante de tus botas y el agua borbotea por debajo.


### Pedido fuera de la ficción

> Decime cuántos puntos de salud tengo y explicame las reglas de Enfrentar el peligro.

**low** · 3.8 s · 9229 → 355 tokens · US$ 0.0004

Herramientas: `get_character` Kira's sheet; `get_move` Rules of Enfrentar el peligro

> Tienes 5 de salud, de un máximo de 5, y estás ilesa: sin marcas ni debilidades.
> 
> Enfrentar el peligro se tira cuando intentas algo arriesgado o reaccionas ante una amenaza inmediata. Eliges la característica según cómo actúas: con velocidad o precisión, filo; con encanto, lealtad o coraje, corazón; con fuerza, agresión o aguante, hierro; con engaño, sigilo o artimañas, sombra; con pericia, intuición u observación, ingenio. Si sale un éxito pleno, logras lo que buscabas y ganas impulso; con un éxito parcial lo logras pero eliges un costo, como perder impulso, recibir daño, sufrir estrés o gastar suministros; y si fallas, pagas el precio.
> 
> Cera sigue esperando tu respuesta junto a la empalizada. ¿Qué haces?

Falló: Largo (2 a 5 oraciones) (6 oraciones); Un solo párrafo; Sin números (5)

**high** · 3.9 s · 9267 → 357 tokens · US$ 0.0007

Herramientas: `get_character` Kira's sheet; `get_move` Rules of Enfrentar el peligro

> Tienes 5 de salud, de un máximo de 5. Enfrentar el peligro se tira cuando haces algo arriesgado o reaccionas a una amenaza: con filo si actúas con velocidad o precisión, corazón si hay coraje o encanto, hierro si es fuerza o aguante, sombra si es engaño o sigilo, e ingenio si es pericia u observación. Con éxito limpio ganas impulso; con éxito parcial logras lo que buscas pero pagas un costo (impulso, salud, ánimo o suministros); si fallas, pagas el precio y la situación empeora. Cera sigue esperando tu respuesta junto a la empalizada, con la mirada fija en el camino del norte. ¿Qué haces?

Falló: Sin números (5)

**max** · 4.6 s · 9438 → 538 tokens · US$ 0.0009

Herramientas: `get_character` Kira's sheet; `get_move` Rules of Enfrentar el peligro

> Tienes 5 de salud, con tu impulso en +1 por encima de tu valor inicial. Enfrentar el peligro se usa cuando intentas algo arriesgado o reaccionas a una amenaza: eliges la estadística según cómo actúes —filo para velocidad o precisión, corazón para encanto o coraje, hierro para fuerza o aguante, sombra para engaño o sigilo, ingenio para pericia u observación—. Si sale bien, ganas impulso; si sale regularmente, lo logras pero eliges un costo: pierdes impulso, recibes daño o estrés, o gastas suministros; si fallas, pagas el precio.
> 
> Cera sigue esperando tu respuesta junto a la empalizada, con el viento del norte golpeando su capa. ¿Qué haces?

Falló: Un solo párrafo; Sin números (5)
