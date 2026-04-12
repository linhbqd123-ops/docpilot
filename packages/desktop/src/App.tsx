import { AppProvider } from "@/app/context";
import { AppLayout } from "@/components/layout/AppLayout";

function App() {
  return (
    <AppProvider>
      <AppLayout />
    </AppProvider>
  );
}

export default App;
