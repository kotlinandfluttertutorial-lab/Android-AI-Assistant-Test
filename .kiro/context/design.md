---
name: Lumina AI
colors:
  surface: '#fdf8fd'
  surface-dim: '#ddd9de'
  surface-bright: '#fdf8fd'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f7f2f8'
  surface-container: '#f1ecf2'
  surface-container-high: '#ebe7ec'
  surface-container-highest: '#e5e1e7'
  on-surface: '#1c1b1f'
  on-surface-variant: '#49454F'
  inverse-surface: '#313034'
  inverse-on-surface: '#f4eff5'
  outline: '#7a7582'
  outline-variant: '#cbc4d2'
  surface-tint: '#6750a4'
  primary: '#4f378a'
  on-primary: '#ffffff'
  primary-container: '#EADDFF'
  on-primary-container: '#e0d2ff'
  inverse-primary: '#cfbcff'
  secondary: '#615c67'
  on-secondary: '#ffffff'
  secondary-container: '#E8DEF8'
  on-secondary-container: '#65616b'
  tertiary: '#765b00'
  on-tertiary: '#ffffff'
  tertiary-container: '#c9a74d'
  on-tertiary-container: '#503d00'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#cfbcff'
  on-primary-fixed: '#22005d'
  on-primary-fixed-variant: '#4f378a'
  secondary-fixed: '#e7e0ec'
  secondary-fixed-dim: '#cbc4d0'
  on-secondary-fixed: '#1d1a23'
  on-secondary-fixed-variant: '#49454f'
  tertiary-fixed: '#ffdf93'
  tertiary-fixed-dim: '#e7c365'
  on-tertiary-fixed: '#241a00'
  on-tertiary-fixed-variant: '#594400'
  background: '#fdf8fd'
  on-background: '#1c1b1f'
  surface-variant: '#e5e1e7'
  surface-light: '#FFFBFD'
  surface-dark: '#0F0F0F'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 57px
    fontWeight: '400'
    lineHeight: 64px
    letterSpacing: -0.25px
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  title-lg:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.5px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.25px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 16px
  margin-mobile: 16px
  margin-desktop: 24px
---

## Brand & Style
The design system is a premium, high-fidelity interpretation of Material Design 3, specifically tailored for an AI Assistant. The brand personality is intelligent, helpful, and quietly sophisticated. It avoids the "robotic" tropes of AI in favor of a human-centric, calm, and professional interface.

The design style is **Minimalist with Material influences**, focusing on heavy whitespace, precise typography, and meaningful motion. It utilizes the "M3" philosophy of tonal surfaces—where depth is communicated through color shifts rather than heavy shadows—resulting in a UI that feels light, responsive, and modern.

## Colors
The color system follows the Material 3 tonal palette logic. The primary purple (`#6750A4`) acts as the anchor for key actions and brand moments. 

In **Light Mode**, the background uses a soft off-white (`#FFFBFD`) to reduce eye strain, while containers use subtle tonal shifts to denote hierarchy. 

In **Dark Mode**, the system shifts to a deep charcoal (`#0F0F0F`) rather than pure black, preserving the ability to show elevation through lighter gray overlays. High-contrast elements use the primary purple to maintain brand recognition across themes.

## Typography
The system utilizes **Inter** exclusively to achieve a clean, systematic appearance that scales perfectly from complex data to simple chat interfaces. 

Headline styles are tight and bold to provide strong structural anchors. Body text uses generous line heights (1.5x) to ensure the AI's long-form responses remain highly readable. For mobile devices, headlines scale down slightly to ensure text doesn't wrap awkwardly in narrow viewports.

## Layout & Spacing
This design system is built on a strict **8pt spacing grid**. All dimensions, padding, and margins are multiples of 8, ensuring visual harmony and rhythmic consistency.

The layout uses a **Fluid Grid** model:
- **Mobile:** 4-column grid with 16px margins and 16px gutters.
- **Tablet:** 8-column grid with 24px margins and 24px gutters.
- **Desktop:** 12-column grid with a max-width of 1440px, centered with flexible margins.

Spacing between chat bubbles should be 8px for grouped messages and 16px for new message blocks to clearly define the conversation flow.

## Elevation & Depth
Depth is primarily conveyed through **Tonal Layers** rather than physical shadows, following M3 principles. 

Surface levels are defined by their color:
- **Level 0 (Background):** Primary surface color.
- **Level 1 (Cards/Chat Bubbles):** +5% primary color tint overlay.
- **Level 2 (Modals/Popups):** +8% primary color tint overlay with a subtle, 16px blur ambient shadow.

For interactive elements like action cards, a very soft, diffused shadow (0px 4px 20px rgba(0,0,0,0.04)) is used to suggest "pressability" without breaking the minimal aesthetic.

## Shapes
The shape language is defined by significant **roundedness** to evoke friendliness and modern tech aesthetics. 

- **Standard Components:** 16px (rounded-lg) corner radius for buttons and input fields.
- **Action Cards & Containers:** 24px (rounded-xl) corner radius to create a distinct, premium look.
- **Chat Bubbles:** 20px radius, with the "tail" corner reduced to 4px to indicate the speaker.
- **Navigation Bars:** Fully pill-shaped (32px+) for active indicator states.

## Components

### Chat Bubbles
- **User Bubble:** Primary color background with white text. Aligned right.
- **AI Bubble:** Surface-variant background with neutral-800 text. Aligned left.
- **Spacing:** 12px internal padding; 8px vertical spacing between consecutive bubbles from the same sender.

### Action Cards
- Used for AI suggestions or tool integrations. 
- 24px corner radius, subtle 1px border (`#49454F` at 10% opacity), and Level 1 elevation.
- Clear "Primary" action button inside the card using the Primary color.

### Mobile Navigation
- Bottom navigation bar using the M3 spec: 80px height, center-aligned icons.
- Active state indicated by a pill-shaped tonal highlight behind the icon.
- Labels use `label-md` and appear only on the active item or all items depending on the view density.

### Input Fields
- Fully rounded (pill-shaped) for the main chat input.
- Leading icon for attachments and trailing icon (Primary color) for the "Send" action.
- Background uses a subtle tonal shift to separate it from the chat canvas.

### Chips & Tags
- Used for quick-reply suggestions. 
- 8px corner radius, `label-lg` typography, and 1px border. On-tap, they fill with a light primary tint.
