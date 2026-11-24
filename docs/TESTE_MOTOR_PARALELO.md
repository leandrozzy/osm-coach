# Modo de teste: motor novo em paralelo

**Desligado por padrão** (Ajustes → "🧪 Modo de teste", ajuste `lab_on`). Local, sem serviço pago nem IA externa. Nunca troca a tática padrão e não aplica nada no OSM.

## O que faz
- Ao gerar a tática (nas 8 h antes do jogo), `EngineLab.recommend` calcula uma segunda recomendação: leitura do rival classificada em atual / antiga / hipótese / desconhecido; cenários do rival ponderados por isso; batalha = rival humano; treino secreto não revela o que não foi lido; objetivo vitória (padrão) ou pontos (`3·P(V)+P(E)`, ajuste `lab_obj_S<slot>` = `pontos`); jogo equilibrado usa 50% média + 50% pior cenário; força confirmada +20 testa também 4-3-3 de emergência (sem forçar); árbitro laranja/vermelho limita o desarme a Cuidadoso; plano B só com ganho ≥ 0,05 e dizendo qual leitura justificaria trocar.
- `EngineLab.freeze` grava o registro `labfreeze_<hash>` ANTES do jogo: versões dos motores, assinatura dos dados, as duas táticas, evidências e as previsões V/E/D dos dois motores para as DUAS táticas. Sem duplicar (mesma assinatura = nada); com dado novo antes do início vira revisão; depois do início ou com resultado lido nunca grava.
- Aba Tática do slot mostra a nova recomendação num bloco fechado "Modo de teste", sem substituir a padrão.

## O que compara (`LabEval`)
Só jogos com resultado, previsões congeladas antes do início e tática APLICADA confirmada (`seguiu`) igual a uma das duas recomendações. Mede Brier e acerto V/E/D das duas previsões para essa mesma tática, com amostra por casa/fora, humano/CPU e faixa de força. Jogos fora da regra são contados por motivo. **Não** mede qual tática ganharia mais: o resultado de uma tática não diz o que a outra faria.

## Como testar depois vitórias/pontos por jogo
1. Ligar o modo e deixar acumular jogos em que o usuário confirma a tática jogada (tabela "Sugerida × jogada").
2. Selecionar jogos comparáveis (mesma faixa de força, casa/fora, humano/CPU) em que o uso real foi confirmado, separando "seguiu o padrão" de "seguiu o novo".
3. Comparar pontos por jogo e % de vitórias entre os dois grupos só com ≥10 jogos por grupo; abaixo disso é ruído. Correlação não é causa: o usuário escolhe quando seguir a sugestão.
4. Só então decidir se o motor novo vira o padrão (mudança que exige pedido explícito do usuário).
