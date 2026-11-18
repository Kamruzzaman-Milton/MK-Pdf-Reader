<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%">
  <defs>
    <!-- AMOLED Obsidian Canvas Gradient -->
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#04060D" />
      <stop offset="50%" stop-color="#020308" />
      <stop offset="100%" stop-color="#090B1A" />
    </linearGradient>

    <!-- Upper-Left Electric Cyan Ambient Glow -->
    <radialGradient id="cyanAura" cx="37%" cy="35%" r="48%">
      <stop offset="0%" stop-color="#00E5FF" stop-opacity="0.22" />
      <stop offset="55%" stop-color="#00E5FF" stop-opacity="0.08" />
      <stop offset="100%" stop-color="#000000" stop-opacity="0" />
    </radialGradient>

    <!-- Lower-Right Neon Violet Ambient Glow -->
    <radialGradient id="violetAura" cx="63%" cy="63%" r="46%">
      <stop offset="0%" stop-color="#7C4DFF" stop-opacity="0.25" />
      <stop offset="55%" stop-color="#7C4DFF" stop-opacity="0.09" />
      <stop offset="100%" stop-color="#000000" stop-opacity="0" />
    </radialGradient>

    <!-- Center Bell Back Halo -->
    <radialGradient id="bellHalo" cx="50%" cy="48%" r="32%">
      <stop offset="0%" stop-color="#00E5FF" stop-opacity="0.26" />
      <stop offset="55%" stop-color="#7C4DFF" stop-opacity="0.12" />
      <stop offset="100%" stop-color="#000000" stop-opacity="0" />
    </radialGradient>

    <!-- Sculpted Bell Body Gradient -->
    <linearGradient id="bellGrad" x1="15%" y1="5%" x2="88%" y2="95%">
      <stop offset="0%" stop-color="#5CF6FF" />
      <stop offset="38%" stop-color="#00E5FF" />
      <stop offset="70%" stop-color="#3D8BFF" />
      <stop offset="100%" stop-color="#7C4DFF" />
    </linearGradient>

    <!-- Frosted Glass Top-Left Specular Bevel -->
    <linearGradient id="glassBevel" x1="10%" y1="0%" x2="85%" y2="100%">
      <stop offset="0%" stop-color="#FFFFFF" stop-opacity="0.48" />
      <stop offset="100%" stop-color="#FFFFFF" stop-opacity="0" />
    </linearGradient>

    <!-- Inner Bell Depth Shadow -->
    <linearGradient id="bellShadow" x1="20%" y1="15%" x2="95%" y2="95%">
      <stop offset="0%" stop-color="#12082E" stop-opacity="0" />
      <stop offset="100%" stop-color="#12082E" stop-opacity="0.26" />
    </linearGradient>

    <!-- Gemini Spark Gradient -->
    <linearGradient id="sparkGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#FFFFFF" />
      <stop offset="60%" stop-color="#E8FDFF" />
      <stop offset="100%" stop-color="#B6F6FF" />
    </linearGradient>

    <!-- Clapper Gradient -->
    <linearGradient id="clapperGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#D1BCFF" />
      <stop offset="100%" stop-color="#7C4DFF" />
    </linearGradient>

    <!-- Acoustic Arc Gradients -->
    <linearGradient id="innerArcGrad" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#B388FF" />
      <stop offset="100%" stop-color="#7C4DFF" />
    </linearGradient>
    <linearGradient id="outerArcGrad" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#40F3FF" />
      <stop offset="60%" stop-color="#00E5FF" />
      <stop offset="100%" stop-color="#7C4DFF" />
    </linearGradient>

    <filter id="softGlow" x="-25%" y="-25%" width="150%" height="150%">
      <feGaussianBlur stdDeviation="8" result="blur" />
      <feMerge>
        <feMergeNode in="blur" />
        <feMergeNode in="SourceGraphic" />
      </feMerge>
    </filter>
  </defs>

  <!-- Rounded Squircle AMOLED Base -->
  <rect x="12" y="12" width="488" height="488" rx="116" fill="url(#bgGrad)" stroke="#1C2338" stroke-width="2.5" />
  <rect x="12" y="12" width="488" height="488" rx="116" fill="url(#cyanAura)" />
  <rect x="12" y="12" width="488" height="488" rx="116" fill="url(#violetAura)" />
  <circle cx="256" cy="246" r="170" fill="url(#bellHalo)" />

  <!-- Acoustic Pulse Arcs (Mapped 1:1 from 108dp adaptive viewport: scale 4.7407) -->
  <g fill="none" stroke-linecap="round" filter="url(#softGlow)">
    <path d="M163.6,203.9 C150.8,221.9 150.8,247.5 163.6,265.5" stroke="url(#innerArcGrad)" stroke-width="11.9" />
    <path d="M348.4,203.9 C361.2,221.9 361.2,247.5 348.4,265.5" stroke="url(#innerArcGrad)" stroke-width="11.9" />
    <path d="M141.3,187.3 C122.3,215.7 122.3,253.6 141.3,282.1" stroke="url(#outerArcGrad)" stroke-width="10.0" opacity="0.92" />
    <path d="M370.7,187.3 C389.7,215.7 389.7,253.6 370.7,282.1" stroke="url(#outerArcGrad)" stroke-width="10.0" opacity="0.92" />
  </g>

  <!-- Bell Crown Loop -->
  <path d="M244.1,158.8 C244.1,142.2 249.4,136.5 256.0,136.5 C262.6,136.5 267.9,142.2 267.9,158.8 Z" fill="#8CF7FF" />

  <!-- Main Bell Body -->
  <path d="M256.0,154.1 C296.3,154.1 320.0,184.9 320.0,227.6 L320.0,256.0 C320.0,277.3 335.6,290.1 339.0,304.4 C341.3,313.8 334.7,320.0 324.7,320.0 L187.3,320.0 C177.3,320.0 170.7,313.8 173.0,304.4 C176.4,290.1 192.0,277.3 192.0,256.0 L192.0,227.6 C192.0,184.9 215.7,154.1 256.0,154.1 Z"
        fill="url(#bellGrad)" filter="url(#softGlow)" />

  <!-- Frosted Glass Bevel & Depth -->
  <path d="M256.0,159.3 C220.0,159.3 197.7,187.3 197.7,227.6 L197.7,256.0 C197.7,275.0 185.8,287.8 181.1,299.6 C202.9,279.7 211.0,256.0 211.0,228.5 C211.0,193.4 228.5,168.8 261.7,160.2 C259.8,159.8 257.9,159.3 256.0,159.3 Z"
        fill="url(#glassBevel)" />
  <path d="M320.0,227.6 L320.0,256.0 C320.0,277.3 335.6,290.1 339.0,304.4 C341.3,313.8 334.7,320.0 324.7,320.0 L246.5,320.0 C279.7,312.9 302.5,291.6 304.4,258.4 L304.4,227.6 C304.4,198.2 291.6,173.0 267.9,158.3 C301.0,165.9 320.0,192.0 320.0,227.6 Z"
        fill="url(#bellShadow)" />
  <line x1="187.3" y1="309.6" x2="324.7" y2="309.6" stroke="#FFFFFF" stroke-width="5.2" stroke-linecap="round" opacity="0.45" />

  <!-- Gemini 4-Point Spark -->
  <path d="M256.0,194.4 C259.8,223.8 274.0,238.0 303.4,241.8 C274.0,245.6 259.8,259.8 256.0,289.2 C252.2,259.8 238.0,245.6 208.6,241.8 C238.0,238.0 252.2,223.8 256.0,194.4 Z"
        fill="url(#sparkGrad)" />

  <!-- Bell Clapper -->
  <path d="M233.2,332.8 L278.8,332.8 C277.8,349.9 268.3,361.2 256.0,361.2 C243.7,361.2 234.2,349.9 233.2,332.8 Z"
        fill="url(#clapperGrad)" />
</svg>
