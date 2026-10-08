# ChatGPT Shader Optimization v1

Teste independente da otimização do Sildur Vibrant Lite.

Base usada:
- Sildur Vibrant Shaders v2.02 Lite.zip
- SHA-256 original: 16746ee127e4c0b07f7e62018589100845338e633eea6bed8cf8c09939aa9eea

Resultado otimizado:
- SHA-256 do ZIP: 0a4b1e73c66cb4033bbac031cf030cfa3fc70b3872828ed4f4bda7225759e1f0

O ZIP otimizado completo fica disponível no chat para teste. Esta pasta no GitHub guarda a documentação e o patch para comparação/revisão.

## Princípios
- otimização matemática/algo antes de redução visual;
- água/reflexos preservados;
- gotículas de chuva/neve preservadas;
- nenhuma alteração intencional de resolução;
- nenhuma remoção de efeito principal;
- Open4Es não foi usado como código.

## Alterações
1. Remoção de pow(x, vec3(1.0)) exato.
2. Quadrado do bloom por multiplicação, reutilizando length.
3. Reuso da distância e do fator exponencial no volumetric lighting.
4. Quadrado do termo de godrays por multiplicação.
5. Remoção de mix(A,B,1.0) exato.

Importante: não foi possível executar Minecraft 26.2/GPU Mali neste ambiente, portanto não há alegação de FPS medido. O teste real no Poco X7 Pro será o juiz final.
